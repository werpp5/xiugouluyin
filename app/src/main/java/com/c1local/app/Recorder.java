package com.c1local.app;

import android.os.Handler;
import android.os.Looper;

import java.io.File;

/**
 * C1 实时录音状态机（官方 3.9.6 协议）。
 *
 * 与 Downloader 的区别：Downloader 是"发下载命令→设备补推已存文件"；
 * Recorder 是"发开始录音命令→设备边录边推实时音频流(b001)→本地落盘解码"。
 *
 * 命令链（抓包实测）：
 *   开始 0a 00 <类型> 00   → 回包 04 START_CNF（带 sid/类型）
 *   音频 b001 数据帧持续推送（帧格式与下载相同：idx3 + len1 + CELT帧）
 *   暂停 03 00 00 00       → 回包 02 PAUSE_IND + 文件尾 0c
 *   续录 0a 00 <类型> 00   → 新分片计数
 *   停止 02 00 00 00       → 回包 03 STOP_IND + 推完余帧 + 文件尾 0c
 *
 * 注意：实时流的 CELT 帧格式与下载流一致（同一 b001 通道、同一 WavFile 解码），
 * 这一点由协议同源推断，尚未用 b001 原始帧逐字节校验；如落盘音频异常，
 * 优先核对 feedFrame 的帧序号配对逻辑。
 */
public class Recorder {
    private final PenLink link;
    private final Downloader.Callback cb;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final File outDir;

    private boolean recording = false;
    private boolean stopping = false;
    private int type = Proto.TYPE_COMMON;
    private int sid = 0;
    private int fileId = 1;                  // 04 START_CNF 给出的分片号；续录会变成 1031 等新值
    private WavFile wav;
    private int frames = 0, pcmTotal = 0, framesReceived = 0;

    // 停止后等待设备推完余帧的兜底看门狗：连续 1.5s 无新帧即收尾
    private static final long STOP_TAIL_MS = 1500;
    private final Runnable stopTail;

    public Recorder(PenLink link, File outDir, Downloader.Callback cb) {
        this.link = link;
        this.outDir = outDir;
        this.cb = cb;
        this.stopTail = () -> {
            if (recording && stopping) {
                cb.onState("停止后余帧接收完毕");
                finish(true, "录音完成");
            }
        };
    }

    public boolean isRecording() { return recording; }

    /** 设备为本次实时录音分配的会话号/分片号，用于把新音频直接挂进录音列表 */
    public int getSid() { return sid; }
    public int getFileId() { return fileId; }

    /** 开始录音。type: 1普通 2采访 3演讲 */
    public void start(int type) {
        if (recording) { cb.onDone(null, false, "正在录音中"); return; }
        this.type = type;
        this.sid = 0;
        this.fileId = 1;
        this.frames = 0; this.pcmTotal = 0; this.framesReceived = 0;
        this.stopping = false;
        try {
            String name = "C1_LIVE_" + (System.currentTimeMillis() / 1000);
            wav = new WavFile(outDir, name);
            if (!wav.open()) { cb.onDone(null, false, "无法创建录音文件（存储权限？）"); return; }
        } catch (Exception e) {
            cb.onDone(null, false, "创建录音文件失败: " + e);
            return;
        }
        recording = true;
        cb.onState("启动录音（类型" + type + "）…");
        // 与下载一样，先重订数据通道确保 b001 推流经可用，再下发开始命令。
        link.resubscribeData(() -> {
            if (!recording) return;
            boolean ok = link.send(Proto.startRecord(type));
            if (!ok) {
                cb.onDone(null, false, "发送开始录音命令失败");
                recording = false;
            }
        });
    }

    /** 暂停录音：回包 02 + 文件尾 0c，音频流暂停但会话未结束 */
    public void pause() {
        if (!recording || stopping) return;
        link.send(Proto.pauseRecord());
        cb.onState("已发暂停命令");
    }

    /** 续录：再发一次开始命令，设备用新分片计数继续推流 */
    public void resume() {
        if (!recording || stopping) return;
        link.send(Proto.startRecord(type));
        cb.onState("已发续录命令");
    }

    /** 停止录音：发 02，等设备推完余帧 + 文件尾后自动收尾 */
    public void stop() {
        if (!recording || stopping) return;
        stopping = true;
        link.send(Proto.stopRecord());
        cb.onState("已发停止命令，等待设备收尾…");
        ui.removeCallbacks(stopTail);
        ui.postDelayed(stopTail, STOP_TAIL_MS);
    }

    /** 由 PenLink 在收到 b001 数据帧时调用（MainActivity 按录音状态转发） */
    public void onDataFrame(byte[] data) {
        if (!recording) return;
        framesReceived++;
        // 每收到一帧就刷新收尾看门狗（停止阶段用）
        if (stopping) {
            ui.removeCallbacks(stopTail);
            ui.postDelayed(stopTail, STOP_TAIL_MS);
        }
        int idx = Proto.frameIndex(data);
        if (idx == 0) {
            // END 帧：文件尾，数据段结束
            int eod = Proto.eodFlag(data);
            if (stopping) {
                finish(eod != 3, eod == 3 ? "设备中止" : "录音完成");
            }
            return;
        }
        int len = Proto.frameLen(data);
        if (len <= 0 || len + 4 > data.length) return;
        byte[] frame = new byte[len];
        System.arraycopy(data, 4, frame, 0, len);
        frames++;
        pcmTotal += wav.feedFrame(idx, frame);
        if (frames % 25 == 0) {
            cb.onProgress(frames, pcmTotal / 64000.0);
        }
    }

    /** 由 PenLink 在收到命令回包时调用 */
    public void onCommand(int cmd, byte[] data) {
        if (!recording) return;
        if (cmd == 0x04) {
            // START_CNF：偏移2起4字节 sid，偏移6起2字节 fileId（新分片号）。
            // 官方抓包：
            //   首次 04 00 <sid4> 01 00 ... → 08 00 <sid4> 01 00 01 00 00 00 ... <type>
            //   续录 04 00 <sid4> 07 04 ... → 08 00 <sid4> 07 04 07 04 00 00 ... <type>
            // 即每次 0a（开始/续录）都必须紧跟 08，且 fid/start 取本次回包的新分片号。
            if (data != null && data.length >= 8) {
                sid = Proto.readLe(data, 2, 4);
                int fid = Proto.readLe(data, 6, 2);
                if (fid > 0) fileId = fid;
            }
            // 新分片从新的帧计数开始，清掉上一片遗留的奇偶配对半块。
            if (wav != null) wav.resetPairing();
            cb.onState("设备已确认录音 sid=" + sid + " 分片=" + fileId);
            if (sid != 0) {
                link.send(Proto.download(sid, fileId, fileId, 0, type));
                cb.onState("已开启实时音频通道 sid=" + sid + " 分片=" + fileId);
            }
        } else if (cmd == 0x02) {
            // PAUSE_IND：设备确认暂停，之后会推 0c 文件尾
            cb.onState("设备已暂停，等待续录");
        } else if (cmd == 0x0c) {
            // FILE_TAIL：分片结束。正常录音期间忽略（续录会有多个），停止阶段交给数据帧 END 收尾
            if (stopping) cb.onState("收到文件尾");
        } else if (cmd == 0x03) {
            // STOP_IND：设备确认停止。给一小段余帧时间再收尾
            if (stopping) {
                cb.onState("设备确认停止");
                ui.removeCallbacks(stopTail);
                ui.postDelayed(stopTail, 600);
            }
        }
    }

    private void finish(boolean success, String msg) {
        if (!recording) return;
        recording = false;
        stopping = false;
        ui.removeCallbacks(stopTail);
        cb.onState("[诊断] 录音收尾: " + msg + " | 帧=" + framesReceived
                + " 有效=" + frames + " PCM=" + (wav == null ? 0 : wav.pcmBytes()) + "B sid=" + sid
                + " 末分片=" + fileId);
        if (wav == null) { cb.onDone(null, false, msg); return; }
        wav.close();
        if (!success || wav.pcmBytes() == 0) {
            cb.onDone(null, false, msg + "（无有效音频）");
            return;
        }
        cb.onDone(wav, true, msg);
    }

    /** 当前已累积音频的单声道 float 样本，供录音面板实时转写使用 */
    public float[] currentSamples() {
        if (wav == null) return new float[0];
        return wav.getFloatSamples();
    }

    /** 已解码的单声道采样点总数 */
    public int sampleCount() {
        return wav == null ? 0 : wav.sampleCount();
    }

    /** 只读 [fromFrame, 末尾) 的新增样本，供实时转写增量识别，避免整段全量转换 */
    public float[] currentSamplesFrom(int fromFrame) {
        if (wav == null) return new float[0];
        return wav.getFloatSamplesRange(fromFrame, wav.sampleCount());
    }

    /** 强制取消（不落盘有效音频） */
    public void cancel() {
        if (!recording) return;
        link.send(Proto.stopRecord());
        finish(false, "已取消录音");
    }
}
