package com.c1local.app;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 把 C1 下载的 CELT 帧解码成 16kHz 单声道 PCM 并写 WAV。
 * 同时保留 float[] 样本供 sherpa-onnx 直接识别。
 */
public class WavFile {
    public static final int SAMPLE_RATE = 16000;
    private AirohaCeltApi api;
    private FileOutputStream pcmOut;
    private final File wavFile;
    private long pcmBytes = 0;
    private int decId = -1;
    private int mode = 2;
    private int channels = 2; // CELT mode=2 输出双声道交错，mode=1 为单声道
    public boolean decoderOk = false;

    // 供本地识别累积的 float 样本
    private final java.io.ByteArrayOutputStream pcmBuffer = new java.io.ByteArrayOutputStream();

    private final byte[] pairBuf = new byte[160];
    private boolean pairOdd = false;

    public WavFile(File dir, String name) throws Exception {
        if (!dir.exists()) dir.mkdirs();
        this.wavFile = new File(dir, name + ".wav");
        if (wavFile.exists()) wavFile.delete();
        wavFile.createNewFile();
    }

    public boolean open() {
        try {
            pcmOut = new FileOutputStream(wavFile);
            try {
                DeviceProfile pf = DeviceProfile.get();
                api = new AirohaCeltApi();
                decId = api.celtDecodeInit(pf.celtMode());
                mode = pf.celtMode();
                if (decId <= -1) {
                    decId = api.celtDecodeInit(pf.celtModeFallback());
                    mode = pf.celtModeFallback();
                }
                decoderOk = decId > -1;
                channels = mode;
            } catch (Throwable t) {
                decoderOk = false;
                channels = 2; // 解码器不可用时仍写一个合法的默认头
            }
            writeHeader(pcmOut, 0); // 头依赖真实声道数，必须在 init 之后写
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 续录/新分片开始时清掉上一片遗留的半块，避免奇偶配对错位 */
    public void resetPairing() {
        pairOdd = false;
    }

    /** feedFrame 处理一帧数据，帧序号奇偶配对成 160B CELT 块 */
    public int feedFrame(int frameIndex, byte[] frame) {
        if (frame.length >= 160) return decodeBlock(frame, 160);
        if (frameIndex % 2 == 1) {
            System.arraycopy(frame, 0, pairBuf, 0, 80);
            pairOdd = true;
            return 0;
        }
        if (pairOdd) {
            System.arraycopy(frame, 0, pairBuf, 80, 80);
        } else {
            System.arraycopy(frame, 0, pairBuf, 0, 80);
            System.arraycopy(frame, 0, pairBuf, 80, 80);
        }
        pairOdd = false;
        return decodeBlock(pairBuf, 160);
    }

    private int decodeBlock(byte[] data, int len) {
        if (!decoderOk || api == null) return 0;
        try {
            short[] pcm = api.celtDecodeProc(data, len, decId, mode);
            if (pcm == null || pcm.length == 0) return 0;
            byte[] bytes = new byte[pcm.length * 2];
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm);
            try {
                pcmOut.write(bytes);
                // 解码线程写、实时转写线程读，必须同步，避免读半截数据
                synchronized (pcmBuffer) { pcmBuffer.write(bytes); }
                pcmBytes += bytes.length;
            } catch (Exception ignored) {}
            return bytes.length;
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * 返回降混为单声道的 float 样本（sherpa-onnx SenseVoice 需要 16kHz 单声道）。
     * CELT mode=2 输出左右声道交错，取两声道平均；mode=1 直接按单声道读。
     */
    public float[] getFloatSamples() {
        byte[] bytes;
        synchronized (pcmBuffer) { bytes = pcmBuffer.toByteArray(); }
        ByteBuffer bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int totalShorts = bytes.length / 2;
        if (channels <= 1) {
            float[] out = new float[totalShorts];
            for (int i = 0; i < totalShorts; i++) out[i] = bb.getShort() / 32768.0f;
            return out;
        }
        int frames = totalShorts / 2;
        float[] out = new float[frames];
        for (int i = 0; i < frames; i++) {
            float l = bb.getShort() / 32768.0f;
            float r = bb.getShort() / 32768.0f;
            out[i] = (l + r) * 0.5f;
        }
        return out;
    }

    /**
     * 只读指定采样点区间并降混成 float，供实时转写"增量识别"使用。
     * 避免每轮都把整段历史音频全量 toByteArray + 全量转换（录久了每轮拷贝几十 MB）。
     * fromFrame/toFrame 均为单声道采样点下标。
     */
    public float[] getFloatSamplesRange(int fromFrame, int toFrame) {
        byte[] bytes;
        synchronized (pcmBuffer) { bytes = pcmBuffer.toByteArray(); }
        int ch = Math.max(1, channels);
        int bytesPerFrame = ch * 2;
        int totalFrames = bytes.length / bytesPerFrame;
        if (fromFrame < 0) fromFrame = 0;
        if (toFrame > totalFrames) toFrame = totalFrames;
        if (toFrame <= fromFrame) return new float[0];
        ByteBuffer bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        bb.position(fromFrame * bytesPerFrame);
        int frames = toFrame - fromFrame;
        float[] out = new float[frames];
        if (ch <= 1) {
            for (int i = 0; i < frames; i++) out[i] = bb.getShort() / 32768.0f;
        } else {
            for (int i = 0; i < frames; i++) {
                float l = bb.getShort() / 32768.0f;
                float r = bb.getShort() / 32768.0f;
                out[i] = (l + r) * 0.5f;
            }
        }
        return out;
    }

    /** 当前已解码的总单声道采样点数 */
    public int sampleCount() {
        return (pcmBuffer.size() / (Math.max(1, channels) * 2));
    }

    public long pcmBytes() { return pcmBytes; }
    public File wav() { return wavFile; }

    /** 已解码 PCM 对应的时长（毫秒），现场录音写入列表时用 */
    public int durationMs() {
        long bytesPerSec = (long) SAMPLE_RATE * Math.max(1, channels) * 2;
        return bytesPerSec <= 0 ? 0 : (int) (pcmBytes * 1000L / bytesPerSec);
    }

    /**
     * 从已保存的 WAV 文件读回单声道 float 样本（离线转写用）。
     * 读取 data 段，按文件头里的声道数降混为单声道。
     */
    public static float[] readFloatSamples(File f) throws Exception {
        byte[] all = new byte[(int) f.length()];
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            int off = 0;
            while (off < all.length) {
                int n = in.read(all, off, all.length - off);
                if (n <= 0) break;
                off += n;
            }
        }
        if (all.length < 44) return new float[0];
        int ch = all[22] & 255;
        if (ch <= 0) ch = 1;
        int dataLen = (all[40] & 255) | ((all[41] & 255) << 8)
                | ((all[42] & 255) << 16) | ((all[43] & 255) << 24);
        int dataOff = 44;
        // 兼容可能存在的扩展头：找 "data" 标记
        for (int p = 12; p + 8 <= all.length - 1 && p < 1024; ) {
            int len = (all[p + 4] & 255) | ((all[p + 5] & 255) << 8)
                    | ((all[p + 6] & 255) << 16) | ((all[p + 7] & 255) << 24);
            if (all[p] == 'd' && all[p + 1] == 'a' && all[p + 2] == 't' && all[p + 3] == 'a') {
                dataOff = p + 8;
                if (len > 0 && len < dataLen) dataLen = len;
                break;
            }
            p += 8 + len + (len & 1);
        }
        if (dataOff + dataLen > all.length) dataLen = Math.max(0, all.length - dataOff);
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(all, dataOff, dataLen)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int totalShorts = dataLen / 2;
        if (ch <= 1) {
            float[] out = new float[totalShorts];
            for (int i = 0; i < totalShorts; i++) out[i] = bb.getShort() / 32768.0f;
            return out;
        }
        int frames = totalShorts / ch;
        float[] out = new float[frames];
        for (int i = 0; i < frames; i++) {
            float acc = 0;
            for (int c = 0; c < ch; c++) acc += bb.getShort() / 32768.0f;
            out[i] = acc / ch;
        }
        return out;
    }

    public void close() {
        try { pcmOut.flush(); pcmOut.close(); } catch (Exception ignored) {}
        try {
            RandomAccessFile raf = new RandomAccessFile(wavFile, "rw");
            raf.seek(4);
            raf.write(le32((int) Math.min(pcmBytes + 36, Integer.MAX_VALUE)));
            raf.seek(40);
            raf.write(le32((int) Math.min(pcmBytes, Integer.MAX_VALUE)));
            raf.close();
        } catch (Exception ignored) {}
    }

    private static byte[] le32(int v) {
        return new byte[]{(byte) v, (byte) (v >> 8), (byte) (v >> 16), (byte) (v >> 24)};
    }

    private void writeHeader(FileOutputStream out, int dataLen) throws Exception {
        int ch = Math.max(1, channels);
        int blockAlign = ch * 2;             // 16bit
        byte[] h = new byte[44];
        System.arraycopy("RIFF".getBytes("US-ASCII"), 0, h, 0, 4);
        put(h, 4, dataLen + 36);
        System.arraycopy("WAVE".getBytes("US-ASCII"), 0, h, 8, 4);
        System.arraycopy("fmt ".getBytes("US-ASCII"), 0, h, 12, 4);
        put(h, 16, 16);
        h[20] = 1; h[21] = 0;                     // PCM
        h[22] = (byte) ch; h[23] = 0;             // 声道数
        put(h, 24, SAMPLE_RATE);
        put(h, 28, SAMPLE_RATE * blockAlign);     // 字节率
        h[32] = (byte) blockAlign; h[33] = 0;     // 块对齐
        h[34] = 16; h[35] = 0;                    // 位深
        System.arraycopy("data".getBytes("US-ASCII"), 0, h, 36, 4);
        put(h, 40, dataLen);
        out.write(h);
    }

    private static void put(byte[] b, int off, int v) {
        b[off] = (byte) v; b[off + 1] = (byte) (v >> 8);
        b[off + 2] = (byte) (v >> 16); b[off + 3] = (byte) (v >> 24);
    }
}
