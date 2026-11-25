package com.c1local.app;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.UUID;

/**
 * C1 录音笔 BLE 连接管理器（从第三方回传 APK 逆向还原）。
 * 含超时重试、MTU 协商、三通道订阅、握手确认——这是能稳定连上笔的关键。
 */
public final class PenLink {
    public static final UUID SVC_MAIN = UUID.fromString("00001910-0000-1000-8000-00805f9b34fb");
    public static final UUID CHR_IND = UUID.fromString("00002bb0-0000-1000-8000-00805f9b34fb");
    public static final UUID CHR_WRITE = UUID.fromString("00002bb1-0000-1000-8000-00805f9b34fb");
    public static final UUID SVC_BAT = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb");
    public static final UUID CHR_BAT = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb");
    public static final UUID SVC_DATA = UUID.fromString("0000cc68-0000-1000-8000-00805f9b34fb");
    public static final UUID CHR_DATA = UUID.fromString("0000b001-0000-1000-8000-00805f9b34fb");
    public static final UUID CCC = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    public interface Listener {
        void onBattery(int pct);
        void onCommand(int cmd, byte[] data);
        void onConnectFailed(String msg);
        void onDataFrame(byte[] data);
        void onDebug(String msg);
        void onDisconnected();
        void onReady();
        void onState(String msg);
    }

    private final Context ctx;
    private final Listener cb;
    private final DeviceProfile pf = DeviceProfile.get();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private BluetoothDevice dev;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic chWrite, chData, batCh, current;
    private boolean currentIndicate;
    private final ArrayList<byte[]> writeQueue = new ArrayList<>();
    private boolean writing = false;
    private int subStep = 0;
    private boolean subStarted = false, ready = false, handshakeDone = false, closing = false;
    private int connectRetry = 0, discoverRetry = 0, descRetry = 0, dataNotifyCount = 0;
    private boolean resubMode = false;
    private int resubPending = 0;
    private Runnable resubDone;

    private final Runnable tConnect = () -> failConnect("连接超时：录音笔无响应（确认开机、未连其他手机）");
    private final Runnable tDiscover = () -> retryDiscover("服务发现超时");
    private final Runnable tMtu = () -> { if (!subStarted) startSub(); };
    private final Runnable tSub = () -> { post("订阅等待超时，继续…"); nextSub(); };
    private final Runnable tWrite = () -> { writing = false; pump(); };
    private final Runnable tHandshake = () -> {
        if (gatt != null && !handshakeDone) {
            post("未收到握手请求，主动发能力确认…");
            sendRaw(Proto.capacitiesConfirm());
            markHandshakeDone();
        }
    };
    private final Runnable readBat = () -> {
        if (gatt != null && batCh != null) {
            try { gatt.readCharacteristic(batCh); } catch (Throwable ignored) {}
        }
    };

    private final BluetoothGattCallback inner = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == 2) {
                if (!closing) {
                    ui.removeCallbacks(tConnect);
                    post("已连上 GATT，发现服务…");
                    discoverRetry = 0;
                    boolean ok = false;
                    try { ok = g.discoverServices(); } catch (Throwable ignored) {}
                    if (ok) { ui.postDelayed(tDiscover, 10000); }
                    else retryDiscover("discoverServices 失败");
                }
            } else if (newState == 0) {
                boolean wasReady = ready;
                ui.removeCallbacks(tConnect); ui.removeCallbacks(tDiscover);
                ui.removeCallbacks(tMtu); ui.removeCallbacks(tSub);
                ui.removeCallbacks(tWrite); ui.removeCallbacks(tHandshake);
                closeQuiet();
                if (wasReady) postDisconnected();
                else if (connectRetry >= 1) failConnect("连接失败(status=" + status + ")：请重新扫描");
                else {
                    connectRetry++;
                    post("连接中断，自动重试…");
                    ui.postDelayed(() -> attemptConnect(), 600);
                }
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (gatt == null) return;
            if (status == 0) {
                ui.removeCallbacks(tDiscover);
                boolean ok = false;
                try { ok = g.requestMtu(256); } catch (Throwable ignored) {}
                if (ok) { ui.postDelayed(tMtu, 4000); }
                else startSub();
            } else retryDiscover("发现服务失败 status=" + status);
        }

        @Override
        public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
            if (gatt == null) return;
            post("MTU=" + mtu + "（若<168，单帧164B音频无法送达）");
            chWrite = find(pf.svcMain(), pf.chrWrite());
            chData = find(pf.svcData(), pf.chrData());
            if (chWrite != null) {
                try { chWrite.setWriteType(2); } catch (Throwable ignored) {}
            }
            startSub();
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            if (gatt == null) return;
            if (!resubMode) { descRetry = 0; nextSub(); }
            else {
                resubPending--;
                post("B001 CCCD status=" + status + " 剩余" + resubPending);
                if (resubPending <= 0) finishResub("已确认");
            }
        }

        @Override
        public void onDescriptorRead(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            if (gatt == null || d == null) return;
            byte[] value = null;
            try { value = d.getValue(); } catch (Throwable ignored) {}
            StringBuilder sb = new StringBuilder();
            if (value != null) for (byte b : value) sb.append(String.format("%02X ", b & 255));
            if (resubMode) {
                resubMode = false; resubPending = 0;
                post("数据通道重订完成");
                Runnable r = resubDone; resubDone = null;
                // 回调可能来自 Binder 线程，必须切回主线程执行，
                // 否则回调里的日志/UI 更新会抛 CalledFromWrongThreadException，
                // 中断 actuallySend 导致下载命令永远发不出去。
                if (r != null) ui.post(r);
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            if (c != null && pf.chrBattery().equals(c.getUuid())) {
                byte[] v = null;
                try { v = c.getValue(); } catch (Throwable ignored) {}
                if (status == 0 && v != null && v.length > 0) {
                    final int pct = v[0] & 255;
                    ui.post(() -> cb.onBattery(pct));
                }
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            ui.removeCallbacks(tWrite);
            writing = false;
            pump();
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            final byte[] value = c.getValue();
            if (value == null || value.length == 0) return;
            UUID uuid = c.getUuid();
            if (pf.chrData().equals(uuid)) {
                dataNotifyCount++;
                ui.post(() -> cb.onDataFrame(value));
                return;
            }
            if (pf.chrBattery().equals(uuid)) {
                final int pct = value[0] & 255;
                ui.post(() -> cb.onBattery(pct));
                return;
            }
            if (pf.chrIndicate().equals(uuid)) {
                int cmd = Proto.readLe(value, 0, 2);
                if (cmd == pf.respCapacitiesReq()) ui.post(() -> onCapacitesReq());
                else ui.post(() -> cb.onCommand(cmd, value));
            }
        }
    };

    public PenLink(Context context, Listener listener) {
        this.ctx = context.getApplicationContext();
        this.cb = listener;
    }

    public boolean isConnected() { return ready; }

    public void connect(BluetoothDevice device) {
        this.dev = device;
        this.connectRetry = 0;
        attemptConnect();
    }

    private void attemptConnect() {
        closing = false; ready = false; handshakeDone = false;
        subStarted = false; subStep = 0; writing = false;
        writeQueue.clear();
        ui.removeCallbacks(tHandshake);
        dataNotifyCount = 0;
        post("正在连接…（第 " + (connectRetry + 1) + " 次）");
        ui.removeCallbacks(tConnect);
        ui.postDelayed(tConnect, 12000);
        BluetoothGatt g = null;
        try { g = dev.connectGatt(ctx, false, inner); } catch (Throwable t) {
            post("connectGatt 异常: " + t);
        }
        if (g == null) failConnect("connectGatt 返回空（蓝牙忙或权限不足）");
        else this.gatt = g;
    }

    private void failConnect(final String msg) {
        ui.removeCallbacks(tConnect); ui.removeCallbacks(tDiscover);
        ui.removeCallbacks(tMtu); ui.removeCallbacks(tSub);
        ui.removeCallbacks(tWrite); ui.removeCallbacks(tHandshake);
        closeQuiet();
        post("❌ " + msg);
        ui.post(() -> cb.onConnectFailed(msg));
    }

    public void close() {
        closing = true;
        ui.removeCallbacks(tConnect); ui.removeCallbacks(tDiscover);
        ui.removeCallbacks(tMtu); ui.removeCallbacks(tSub);
        ui.removeCallbacks(tWrite); ui.removeCallbacks(tHandshake);
        closeQuiet();
    }

    private void closeQuiet() {
        writeQueue.clear();
        if (gatt != null) {
            try { gatt.disconnect(); } catch (Throwable ignored) {}
            try { gatt.close(); } catch (Throwable ignored) {}
            gatt = null;
        }
        writing = false; ready = false; handshakeDone = false; subStarted = false;
    }

    public boolean send(byte[] data) {
        if (gatt == null || chWrite == null || !ready) return false;
        return sendRaw(data);
    }

    private boolean sendRaw(byte[] data) {
        if (gatt == null || chWrite == null) return false;
        synchronized (writeQueue) { writeQueue.add(data); }
        pump();
        return true;
    }

    private void pump() {
        if (writing || gatt == null || chWrite == null) return;
        synchronized (writeQueue) {
            if (writeQueue.isEmpty()) return;
            byte[] data = writeQueue.remove(0);
            writing = true;
            ui.removeCallbacks(tWrite);
            chWrite.setValue(data);
            boolean ok = false;
            try { ok = gatt.writeCharacteristic(chWrite); } catch (Throwable ignored) {}
            if (ok) ui.postDelayed(tWrite, 1200);
            else {
                writing = false;
                ui.postDelayed(this::pump, 50);
            }
        }
    }

    private void setNotify(BluetoothGattCharacteristic c, boolean indicate) {
        if (c == null) { nextSub(); return; }
        try { gatt.setCharacteristicNotification(c, true); } catch (Throwable ignored) {}
        BluetoothGattDescriptor d = c.getDescriptor(pf.chrCccd());
        if (d == null) { nextSub(); return; }
        d.setValue(indicate ? BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                : BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        boolean ok = false;
        try { ok = gatt.writeDescriptor(d); } catch (Throwable ignored) {}
        if (ok) { descRetry = 0; ui.removeCallbacks(tSub); ui.postDelayed(tSub, 4000); }
        else if (++descRetry <= 3) ui.postDelayed(() -> setNotify(current, currentIndicate), 200);
        else { descRetry = 0; nextSub(); }
    }

    private void nextSub() {
        ui.removeCallbacks(tSub);
        subStep++;
        if (gatt == null) return;
        switch (subStep) {
            case 1:
                post("订阅命令通道…");
                current = find(pf.svcMain(), pf.chrIndicate()); currentIndicate = true;
                setNotify(current, true); break;
            case 2:
                post("订阅数据通道…");
                current = find(pf.svcData(), pf.chrData()); currentIndicate = false;
                setNotify(current, false); break;
            case 3:
                post("订阅电量…");
                batCh = find(pf.svcBattery(), pf.chrBattery());
                current = batCh; currentIndicate = false;
                setNotify(current, false);
                if (batCh != null) {
                    ui.postDelayed(readBat, 600); ui.postDelayed(readBat, 5000);
                } else post("无电量服务，不显示电量");
                break;
            default:
                if (!ready) {
                    ready = true;
                    post("通道就绪，等待握手…");
                    ui.removeCallbacks(tHandshake);
                    ui.postDelayed(tHandshake, 3000);
                }
                break;
        }
    }

    private void onCapacitesReq() {
        sendRaw(Proto.capacitiesConfirm());
        markHandshakeDone();
    }

    private void markHandshakeDone() {
        if (handshakeDone) return;
        handshakeDone = true;
        ui.removeCallbacks(tHandshake);
        ui.postDelayed(() -> {
            if (gatt != null) {
                post("握手完成，读取录音列表…");
                if (batCh != null) { ui.postDelayed(readBat, 300); ui.postDelayed(readBat, 6000); }
                cb.onReady();
            }
        }, 400);
    }

    /** 下载前重订数据通道（C1 的怪癖，必须重订才能推流） */
    public void resubscribeData(Runnable done) {
        resubDone = done;
        resubPending = 0;
        if (gatt == null) { if (done != null) done.run(); return; }
        ArrayList<BluetoothGattCharacteristic> found = new ArrayList<>();
        try {
            for (BluetoothGattService s : gatt.getServices()) {
                if (s == null) continue;
                for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                    if (c != null && pf.chrData().equals(c.getUuid())) found.add(c);
                }
            }
        } catch (Throwable ignored) {}
        post("重订数据通道: " + found.size() + " 个B001");
        if (found.isEmpty()) { ui.postDelayed(() -> finishResub("未找到B001"), 100); return; }
        resubMode = true;
        for (BluetoothGattCharacteristic c : found) {
            try { gatt.setCharacteristicNotification(c, true); } catch (Throwable ignored) {}
            BluetoothGattDescriptor d = c.getDescriptor(pf.chrCccd());
            if (d == null) continue;
            d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            boolean ok = false;
            try { ok = gatt.writeDescriptor(d); } catch (Throwable ignored) {}
            if (ok) resubPending++;
        }
        if (resubPending == 0) ui.postDelayed(() -> finishResub("写入未排队"), 100);
    }

    private void finishResub(String msg) {
        if (!resubMode) return;
        BluetoothGattCharacteristic c = find(pf.svcData(), pf.chrData());
        BluetoothGattDescriptor d = c == null ? null : c.getDescriptor(pf.chrCccd());
        boolean ok = false;
        if (d != null) { try { ok = gatt.readDescriptor(d); } catch (Throwable ignored) {} }
        if (!ok) {
            resubMode = false; resubPending = 0;
            Runnable r = resubDone; resubDone = null;
            if (r != null) ui.post(r); // 同样切回主线程，避免 UI 更新异常中断下载
        }
    }

    public void readDataCccd() {
        if (gatt == null) return;
        BluetoothGattCharacteristic c = find(pf.svcData(), pf.chrData());
        if (c == null) return;
        BluetoothGattDescriptor d = c.getDescriptor(pf.chrCccd());
        if (d == null) return;
        try { gatt.readDescriptor(d); } catch (Throwable ignored) {}
    }

    private void startSub() {
        if (!subStarted) { subStarted = true; ui.removeCallbacks(tMtu); subStep = 0; nextSub(); }
    }

    private void retryDiscover(String msg) {
        if (gatt != null && !ready) {
            if (++discoverRetry <= 2) {
                post(msg + "，重试…");
                try { gatt.discoverServices(); } catch (Throwable ignored) {}
                ui.removeCallbacks(tDiscover);
                ui.postDelayed(tDiscover, 10000);
            } else failConnect(msg + "（多次失败）");
        }
    }

    private BluetoothGattCharacteristic find(UUID svc, UUID chr) {
        try {
            BluetoothGattService s = gatt.getService(svc);
            return s == null ? null : s.getCharacteristic(chr);
        } catch (Throwable t) { return null; }
    }

    private void post(final String msg) { ui.post(() -> cb.onState(msg)); }
    private void postDisconnected() { ui.post(() -> cb.onDisconnected()); }
}
