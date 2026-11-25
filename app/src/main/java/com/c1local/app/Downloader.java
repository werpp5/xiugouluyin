package com.c1local.app;

import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.util.ArrayList;

/**
 * C1 文件下载状态机。
 * 关键：C1 对下载参数（rt/结束索引/公式）敏感，需按多组组合试探，
 * 收到数据帧即锁定可用组合。这是逆向出的核心诀窍。
 */
public class Downloader {
    private static final int ATTEMPTS = 40;

    public interface Callback {
        void onState(String msg);
        void onProgress(int frames, double seconds);
        void onDone(WavFile wav, boolean success, String msg);
    }

    private final PenLink link;
    private final Callback cb;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final File outDir;

    private boolean downloading = false;
    private boolean headerOk = false;
    private WavFile wav;
    private int sid, fid, rt, dur;
    private int frames = 0, pcmTotal = 0;
    private int framesReceived = 0;
    private int attempt = 0;
    private int lastCombo = -1;
    // 已验证的可用组合，后续优先使用
    private int wonRt = -1, wonFormula = -1;
    // 40 组试探顺序：rt 与结束索引公式的组合（原版精确算法）
    private final int[] rtOrder = new int[ATTEMPTS];
    private final int[] fOrder = new int[ATTEMPTS];

    private final Runnable tHeader = () -> {
        if (downloading && !headerOk) nextAttempt("等待 HEADER 超时");
    };
    private final Runnable tWatchdog = () -> {
        if (downloading && frames > 0) finish(true, "传输完成");
    };
    private final Runnable tAttempt = () -> tryAttempt();
    private final Runnable tGo = () -> resubscribeAndSend();
    // 重订数据通道若迟迟不回调，兜底直接发命令，避免卡死
    private final Runnable tGoFallback = () -> {
        if (downloading && !headerOk) actuallySend();
    };

    public Downloader(PenLink link, File outDir, Callback cb) {
        this.link = link;
        this.outDir = outDir;
        this.cb = cb;
    }

    public boolean isDownloading() { return downloading; }

    public void start(int sid, int fid, int rt, int dur) {
        this.sid = sid; this.fid = fid; this.rt = rt; this.dur = dur;
        this.frames = 0; this.pcmTotal = 0; this.attempt = 0; this.framesReceived = 0;
        this.headerOk = false; this.downloading = true; this.sentThisAttempt = false;
        cb.onState("[诊断] 开始下载 sid=" + sid + " fid=" + fid + " rt=" + rt + " dur=" + dur);
        try {
            // 稳定文件名：按 sid-fid 命名，便于离线播放/重命名/精确删除时定位
            wav = new WavFile(outDir, "C1_" + sid + "_" + fid);
            if (!wav.open()) { cb.onDone(null, false, "无法创建音频文件（存储权限？）"); return; }
        } catch (Exception e) {
            cb.onDone(null, false, "创建文件失败: " + e);
            return;
        }
        buildOrder(rt);
        if (wonRt > 0) {
            // 用上次验证的组合优先
            for (int i = 0; i < ATTEMPTS; i++) {
                if (rtOrder[i] == wonRt && fOrder[i] == wonFormula) { attempt = i; break; }
            }
        }
        tryAttempt();
    }

    /** 按原版算法构造 40 组试探顺序：以真实 rt 打头，formula 0-4，再遍历其他 rt */
    private void buildOrder(int realRt) {
        int n = 0;
        if (realRt >= 1 && realRt <= 8) {
            for (int f = 0; f <= 4; f++) { rtOrder[n] = realRt; fOrder[n] = f; n++; }
        }
        for (int r = 1; r <= 8 && n < ATTEMPTS; r++) {
            if (r != realRt) {
                for (int f = 0; f <= 4 && n < ATTEMPTS; f++) { rtOrder[n] = r; fOrder[n] = f; n++; }
            }
        }
        while (n < ATTEMPTS) { rtOrder[n] = 1; fOrder[n] = 0; n++; }
    }

    private void tryAttempt() {
        cb.onState("回传中 会话" + sid + "/" + fid + "（组合 " + (attempt + 1) + "/" + ATTEMPTS + "）");
        // 预查询文件表确认目标存在，然后重订数据通道并发真正的下载命令。
        // 注意：这里必须走 tGo(resubscribeAndSend)，不能 post tAttempt 自己——
        // 否则会陷入"只刷日志、永远不发下载命令"的死循环。
        link.send(Proto.getFiles(sid, rt));
        ui.postDelayed(tGo, 400);
        ui.postDelayed(tGoFallback, 1500);
    }

    private void resubscribeAndSend() {
        link.resubscribeData(this::actuallySend);
    }

    private boolean sentThisAttempt = false;
    private void actuallySend() {
        if (sentThisAttempt) return;   // 正常回调与兜底只生效一次
        sentThisAttempt = true;
        ui.removeCallbacks(tGoFallback);
        lastCombo = attempt;
        int formula = fOrder[attempt];
        int end = calcEnd(formula, fid, dur);
        int useRt = rtOrder[attempt];
        cb.onState("下载尝试" + (attempt + 1) + "/" + ATTEMPTS + ": fid=" + fid + " " + fid + "-" + end + " rt=" + useRt);
        boolean ok = link.send(Proto.download(sid, fid, fid, end, useRt));
        if (ok) { ui.removeCallbacks(tHeader); ui.postDelayed(tHeader, 1800); }
        else cb.onDone(null, false, "发送下载命令失败");
    }

    private void nextAttempt(String reason) {
        cb.onState("组合" + (attempt + 1) + "被拒: " + reason);
        ui.removeCallbacks(tGo); ui.removeCallbacks(tGoFallback);
        attempt++;
        sentThisAttempt = false;
        if (attempt >= ATTEMPTS) {
            finish(false, "所有组合均被录音笔拒绝");
        } else {
            ui.postDelayed(tAttempt, 180);
        }
    }

    /** 由 PenLink 在收到命令应答时调用 */
    public void onCommand(int cmd, byte[] data) {
        if (!downloading) return;
        cb.onState("[诊断] 收到命令应答 cmd=" + cmd + " len=" + data.length
                + " data=" + hex(data, 12));
        if (cmd == 11) {
            // HEADER: 录音笔接受
            int ok = data.length > 2 ? (data[2] & 255) : 0;
            if (ok == 0) { nextAttempt("HEADER=0 拒绝"); return; }
            headerOk = true;
            ui.removeCallbacks(tHeader);
            cb.onState("录音笔已接受，开始传输…");
        } else if (cmd == 12) {
            int eod = data.length > 2 ? (data[2] & 255) : 0;
            if (!headerOk) return;
            if (eod == 3) finish(false, "录音笔中止传输");
            else finish(true, "传输完成");
        } else if (cmd == 10) {
            // 下载前预查询应答：列表阶段 dur 可能因会话错位而偏小，
            // 这里用录音笔返回的文件真实时长覆盖，避免只下载开头一小段。
            ArrayList<int[]> fs = Proto.parseFiles(data);
            for (int[] f : fs) {
                if (f[0] == fid && f[1] > 0 && f[1] < 3600000) {
                    if (f[1] != dur) {
                        cb.onState("[诊断] 预查询修正 dur " + dur + " → " + f[1]);
                        dur = f[1];
                    }
                    break;
                }
            }
        } else if (cmd == 9) {
            // 会话确认（初始化阶段），下载中忽略
        }
    }

    /** 由 PenLink 在收到数据帧时调用 */
    public void onDataFrame(byte[] data) {
        if (!downloading) return;
        framesReceived++;
        if (framesReceived <= 3) cb.onState("[诊断] 收到数据帧#" + framesReceived
                + " len=" + data.length + " head=" + hex(data, 8));
        int idx = Proto.frameIndex(data);
        // 数据帧先于 HEADER 到达也算接受
        if (!headerOk && idx != 0) {
            headerOk = true;
            int lc = lastCombo < 0 ? 0 : lastCombo;
            wonRt = rtOrder[lc];
            wonFormula = fOrder[lc];
            ui.removeCallbacks(tHeader);
            ui.removeCallbacks(tAttempt);
            cb.onState("★ 命中组合" + (lastCombo + 1) + "，开始接收数据");
        }
        ui.removeCallbacks(tWatchdog);
        ui.postDelayed(tWatchdog, 6000);

        if (idx == 0) {
            int eod = Proto.eodFlag(data);
            finish(eod != 3, eod == 3 ? "录音笔中止" : "END帧收尾");
            return;
        }
        int len = Proto.frameLen(data);
        if (len <= 0 || len + 4 > data.length) return;
        byte[] frame = new byte[len];
        System.arraycopy(data, 4, frame, 0, len);
        if (frames == 0 && lastCombo >= 0) {
            wonRt = rtOrder[lastCombo];
            wonFormula = fOrder[lastCombo];
        }
        frames++;
        pcmTotal += wav.feedFrame(idx, frame);
        if (frames % 20 == 0) {
            cb.onProgress(frames, pcmTotal / 64000.0);
        }
    }

    private void finish(boolean success, String msg) {
        if (!downloading) return;
        cb.onState("[诊断] finish: " + msg + " | 数据帧=" + framesReceived
                + " 有效帧=" + frames + " PCM=" + pcmBytesOf(wav) + "B 命中组合=" + (lastCombo + 1));
        downloading = false;
        ui.removeCallbacks(tHeader); ui.removeCallbacks(tWatchdog);
        ui.removeCallbacks(tAttempt); ui.removeCallbacks(tGo);
        if (wav != null) {
            wav.close();
            if (!success || wav.pcmBytes() == 0) {
                cb.onDone(null, false, msg + "（无有效音频）");
                return;
            }
            cb.onDone(wav, true, msg);
        } else {
            cb.onDone(null, false, msg);
        }
    }

    public void cancel() {
        if (!downloading) return;
        link.send(Proto.downloadStop());
        finish(false, "已取消");
    }

    private static String hex(byte[] b, int max) {
        if (b == null) return "null";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(max, b.length); i++) {
            sb.append(String.format("%02x", b[i] & 255));
        }
        return sb.toString();
    }

    private static long pcmBytesOf(WavFile w) { return w == null ? 0 : w.pcmBytes(); }

    // ---- C1 结束索引公式（逆向还原，dur 通常为时长或帧数参数） ----
    private int calcEnd(int formula, int fid, int dur) {
        switch (formula) {
            case 0: return (dur / 10 + fid) - 1;
            case 1: return (dur / 80 + fid) - 1;
            case 2: return fid + dur - 1;
            case 3: return (dur / 20 + fid) - 1;
            default: return 0;
        }
    }
}
