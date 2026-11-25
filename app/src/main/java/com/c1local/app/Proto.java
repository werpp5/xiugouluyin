package com.c1local.app;

import java.util.ArrayList;

/**
 * C1 录音笔蓝牙协议命令构造与解析。
 * 从第三方回传 APK 逆向还原，命令格式为 20 字节定长包，小端序。
 */
public final class Proto {
    public static final int CMD_CAPACITES = 14;          // 0x0e 能力协商
    public static final int CMD_DOWNLOAD_FILE = 8;       // 0x08 下载文件
    public static final int CMD_DOWNLOAD_STOP = 2;       // 0x02 停止(=官方 STOP；原 0x09 是设备回包,App 发它无效)
    public static final int CMD_FILES_CONFIRM = 10;      // 0x0a 文件确认(回包)
    public static final int CMD_FILE_HEADER = 11;        // 0x0b 文件头(回包)
    public static final int CMD_FILE_TAIL = 12;          // 0x0c 文件尾(回包)
    public static final int CMD_FINISH_FILE = 12;
    public static final int CMD_GET_FILES = 7;           // 0x07 查询文件分片
    public static final int CMD_GET_SESSIONS = 6;        // 0x06 查询会话
    public static final int CMD_SESSIONS_CONFIRM = 9;    // 0x09 会话确认(回包)

    // ---- 官方 3.9.6 抓包新增 ----
    public static final int CMD_STOP_RECORD = 0x02;      // 停止录音 / 停止传输
    public static final int CMD_PAUSE_RECORD = 0x03;     // 暂停录音
    public static final int CMD_START_RECORD = 0x0a;     // 开始 / 续录录音(带类型字节)
    public static final int CMD_SET_LED_HIDE = 0x1b;     // 隐蔽录音 / LED 隐藏
    public static final int CMD_GET_FREE_SIZE = 0x1c;    // 查询存储空间

    public static final int TYPE_COMMON = 1;             // 普通录音
    public static final int TYPE_INTERVIEW = 2;          // 采访速记
    public static final int TYPE_SPEECH = 3;             // 演讲速记

    public static byte[] le(int v, int len) {
        byte[] b = new byte[len];
        for (int i = 0; i < 4 && i < len; i++) {
            b[i] = (byte) ((v >> (i * 8)) & 255);
        }
        return b;
    }

    public static int readLe(byte[] b, int off, int len) {
        int r = 0;
        for (int i = 0; i < len; i++) {
            r |= (b[off + i] & 255) << (i * 8);
        }
        return r;
    }

    private static byte[] cmd(int c) {
        byte[] b = new byte[20];
        System.arraycopy(le(c, 2), 0, b, 0, 2);
        return b;
    }

    public static byte[] getSessions() {
        byte[] b = cmd(DeviceProfile.get().cmdGetSessions());
        System.arraycopy(le(0, 4), 0, b, 2, 4);
        return b;
    }

    public static byte[] getFiles(int sid, int rt) {
        byte[] b = cmd(DeviceProfile.get().cmdGetFiles());
        System.arraycopy(le(sid, 4), 0, b, 2, 4);
        System.arraycopy(le(rt, 1), 0, b, 6, 1);
        return b;
    }

    public static byte[] download(int sid, int fid, int start, int end, int rt) {
        byte[] b = cmd(DeviceProfile.get().cmdDownloadFile());
        System.arraycopy(le(sid, 4), 0, b, 2, 4);
        System.arraycopy(le(fid, 2), 0, b, 6, 2);
        System.arraycopy(le(start, 4), 0, b, 8, 4);
        System.arraycopy(le(end, 4), 0, b, 12, 4);
        System.arraycopy(le(rt, 1), 0, b, 16, 1);
        return b;
    }

    public static byte[] downloadStop() {
        // 0x09 是设备的“会话确认”回包，App 发它无效；官方停止/停传命令是 0x02
        return cmd(DeviceProfile.get().cmdDownloadStop());
    }

    public static byte[] getSSN() {
        return cmd(DeviceProfile.get().cmdGetSSN());
    }

    public static byte[] finishFile(int sid, int fid) {
        byte[] b = cmd(DeviceProfile.get().cmdFinishFile());
        System.arraycopy(le(sid, 4), 0, b, 2, 4);
        System.arraycopy(le(fid, 2), 0, b, 6, 2);
        return b;
    }

    /**
     * 能力协商确认。补齐官方 3.9.6 完整串：
     * 0e 00 01 01 03 00 c0 cb 01 01 "11d4b8aa4f"
     * 字节6~7=c0 cb，偏移10起为 ASCII 设备标识。此前自研只填前半也能连上，
     * 这里对齐官方以降低个别固件握手失败的概率。
     */
    public static byte[] capacitiesConfirm() {
        byte[] b = cmd(DeviceProfile.get().cmdCapacities());
        b[2] = 1; b[3] = 1; b[4] = 3; b[5] = 0;
        b[6] = (byte) 0xc0; b[7] = (byte) 0xcb;
        b[8] = 1; b[9] = 1;
        byte[] tag = DeviceProfile.get().capacitiesTag().getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        System.arraycopy(tag, 0, b, 10, Math.min(tag.length, b.length - 10));
        return b;
    }

    // ==================== 官方 3.9.6 新增命令 ====================

    /** 开始 / 续录录音：0a 00 <类型> 00。类型 1普通 2采访 3演讲 */
    public static byte[] startRecord(int type) {
        byte[] b = cmd(DeviceProfile.get().cmdStartRecord());
        b[2] = (byte) (type & 255);
        return b;
    }

    /** 暂停录音：03 00 00 00 */
    public static byte[] pauseRecord() { return cmd(DeviceProfile.get().cmdPauseRecord()); }

    /** 停止录音：02 00 00 00 */
    public static byte[] stopRecord() { return cmd(DeviceProfile.get().cmdStopRecord()); }

    /** 查询录音文件分片：07 00 <sid 4B> <序号 2B>。停止后补拉用 */
    public static byte[] queryParts(int sid) {
        byte[] b = cmd(DeviceProfile.get().cmdQueryParts());
        System.arraycopy(le(sid, 4), 0, b, 2, 4);
        return b;
    }

    /** 查询用户设置(含 LED/隐蔽状态)：1a 00 00 00 → 设备回 0x18 */
    public static byte[] getUserSet() { return cmd(DeviceProfile.get().cmdGetUserSet()); }

    /** 隐蔽录音 / LED 隐藏：1b 00 <01开/00关> 00 */
    public static byte[] setLedHide(boolean hide) {
        byte[] b = cmd(DeviceProfile.get().cmdSetLedHide());
        b[2] = (byte) (hide ? 1 : 0);
        return b;
    }

    /** 查询存储空间：1c 00 00 00 */
    public static byte[] getFreeSize() { return cmd(DeviceProfile.get().cmdGetFreeSize()); }

    /**
     * 解析空间回包 0x19。偏移2/6/10 三个无符号小端 int：
     * total(KB)、free(KB)、second(可录音秒数)。
     * 返回 long[]{totalBytes, freeBytes, seconds}。
     */
    public static long[] parseFreeSize(byte[] b) {
        if (b == null || b.length < 14) return new long[]{0, 0, 0};
        long total = readLe(b, 2, 4) & 0xffffffffL;   // KB
        long free = readLe(b, 6, 4) & 0xffffffffL;    // KB
        long sec = readLe(b, 10, 4) & 0xffffffffL;    // 秒
        return new long[]{total * 1024L, free * 1024L, sec};
    }

    /**
     * 解析 LED/隐蔽状态回包 0x18。
     * 真机校验：设备正常亮灯时查询回包为 18 00 00 01，即字节3 表示"指示灯亮"（1=亮=未隐蔽）。
     * 隐蔽录音=关灯，故字节3=0 才代表隐蔽开启。之前按 1=隐蔽 解析导致灯亮却显示"开"。
     */
    public static boolean parseLedHide(byte[] b) {
        return b != null && b.length > 3 && (b[3] & 255) == 0;
    }

    /** 解析会话列表。返回 [sid, info, rt]。 */
    public static ArrayList<int[]> parseSessions(byte[] b) {
        return parseSessionsStride(b, DeviceProfile.get().sessionStrides()[0]);
    }

    public static ArrayList<int[]> parseSessionsStride(byte[] b, int stride) {
        ArrayList<int[]> out = new ArrayList<>();
        DeviceProfile pf = DeviceProfile.get();
        int limit = stride == 12 ? pf.sessionLimit12() : (stride == 8 ? pf.sessionLimit8() : pf.sessionLimit4());
        int p = 2;
        while (p + stride <= Math.min(limit, b.length)) {
            int sid = readLe(b, p, 4);
            if (sid == 0) break;
            out.add(new int[]{sid,
                    stride >= 8 ? readLe(b, p + 4, 4) : 0,
                    stride >= 12 ? readLe(b, p + 8, 4) : 1});
            p += stride;
        }
        return out;
    }

    public static ArrayList<int[]> parseSessionsAny(byte[] b) {
        int[] strides = DeviceProfile.get().sessionStrides();
        for (int s : strides) {
            ArrayList<int[]> r = parseSessionsStride(b, s);
            if (!r.isEmpty()) return r;
        }
        return new ArrayList<>();
    }

    /** 解析文件列表。返回 [fid, size]。 */
    public static ArrayList<int[]> parseFiles(byte[] b) {
        ArrayList<int[]> out = new ArrayList<>();
        DeviceProfile pf = DeviceProfile.get();
        int step = pf.fileStep();
        for (int p = 2; p <= pf.fileLimit() && p + step <= b.length; p += step) {
            int fid = readLe(b, p, 2);
            if (fid == 0) break;
            if (fid != 65535) out.add(new int[]{fid, readLe(b, p + 2, 4)});
        }
        return out;
    }

    public static int frameIndex(byte[] b) { return readLe(b, 0, 3); }
    public static int frameLen(byte[] b) { return b[3] & 255; }
    public static boolean isEndFrame(byte[] b) { return frameIndex(b) == 0; }
    public static int eodFlag(byte[] b) { return b.length > 4 ? (b[4] & 255) : 0; }

    public static String hex(byte[] b) {
        if (b == null) return "";
        int n = Math.min(b.length, 24);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(String.format("%02X ", b[i] & 255));
        return sb.toString().trim();
    }

    private Proto() {}
}
