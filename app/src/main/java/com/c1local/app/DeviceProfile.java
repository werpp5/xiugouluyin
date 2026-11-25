package com.c1local.app;

import android.content.res.AssetManager;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 设备档案：把原来散落在 Proto / PenLink / WavFile 里的型号相关常量收敛成数据。
 *
 * 设计目标：
 *  - 加新型号 = 加一份 assets/profiles/xxx.json，不改 Java 代码。
 *  - 当前工程只内置 c1.json；JSON 读不到或字段缺失时全部回退到内置默认值，
 *    保证与档案化之前的行为完全一致（纯重构，零行为变化）。
 *
 * 注意：这是第一步"地基"。自动识别 + 采集模式在后续步骤接入，
 * 现在 load() 固定读 c1，外部 API 保持原有签名不变。
 */
public final class DeviceProfile {

    // ---------- 内置默认（档案缺失时的兜底，值 = 档案化前的硬编码）----------
    private String id = "c1";
    private String name = "搜狗录音笔 C1 / C18D";
    private List<String> namePrefixes = new ArrayList<>();

    private UUID uuidServiceMain = u("1910");
    private UUID uuidWrite = u("2bb1");
    private UUID uuidIndicate = u("2bb0");
    private UUID uuidServiceBattery = u("180f");
    private UUID uuidBattery = u("2a19");
    private UUID uuidServiceData = u("cc68");
    private UUID uuidData = u("b001");
    private UUID uuidCccd = u("2902");

    private int cmdCapacities = 14, cmdDownloadFile = 8, cmdDownloadStop = 2;
    private int cmdGetFiles = 7, cmdGetSessions = 6, cmdGetSSN = 17, cmdFinishFile = 12;
    private int cmdStartRecord = 10, cmdPauseRecord = 3, cmdStopRecord = 2;
    private int cmdQueryParts = 7, cmdGetUserSet = 26, cmdSetLedHide = 27, cmdGetFreeSize = 28;
    private int respCapacitiesReq = 14;

    private String capacitiesTag = "11d4b8aa4f";

    private int[] sessionStrides = {12, 8, 4};
    private int sessionLimit12 = 70, sessionLimit8 = 74, sessionLimit4 = 14;
    private int fileStep = 6, fileLimit = 14;

    private int celtMode = 2, celtModeFallback = 1, sampleRate = 16000;
    private int framePairBytes = 160, minMtu = 168;

    private String modelDir = "sensevoice";
    private String modelFile = "model.int8.onnx";
    private String modelTokens = "tokens.txt";
    private long modelMinBytes = 200_000_000L;
    private String modelVersion = "int8-v1";

    private static DeviceProfile current;

    /** 应用启动时调用一次，固定加载 c1 档案 */
    public static void load(AssetManager assets) {
        DeviceProfile p = new DeviceProfile();
        try {
            InputStream in = assets.open("profiles/c1.json");
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            p.apply(new JSONObject(new String(bos.toByteArray(), StandardCharsets.UTF_8)));
        } catch (Throwable t) {
            android.util.Log.w("DeviceProfile", "档案加载失败，使用内置默认: " + t);
        }
        current = p;
    }

    public static DeviceProfile get() {
        if (current == null) current = new DeviceProfile();
        return current;
    }

    private void apply(JSONObject j) {
        id = j.optString("id", id);
        name = j.optString("name", name);
        org.json.JSONArray pa = j.optJSONArray("namePrefixes");
        if (pa != null) {
            namePrefixes.clear();
            for (int i = 0; i < pa.length(); i++) namePrefixes.add(pa.optString(i));
        }
        JSONObject u = j.optJSONObject("uuids");
        if (u != null) {
            uuidServiceMain = uOpt(u, "serviceMain", uuidServiceMain);
            uuidWrite = uOpt(u, "write", uuidWrite);
            uuidIndicate = uOpt(u, "indicate", uuidIndicate);
            uuidServiceBattery = uOpt(u, "serviceBattery", uuidServiceBattery);
            uuidBattery = uOpt(u, "battery", uuidBattery);
            uuidServiceData = uOpt(u, "serviceData", uuidServiceData);
            uuidData = uOpt(u, "data", uuidData);
            uuidCccd = uOpt(u, "cccd", uuidCccd);
        }
        JSONObject c = j.optJSONObject("commands");
        if (c != null) {
            cmdCapacities = c.optInt("capacities", cmdCapacities);
            cmdDownloadFile = c.optInt("downloadFile", cmdDownloadFile);
            cmdDownloadStop = c.optInt("downloadStop", cmdDownloadStop);
            cmdGetFiles = c.optInt("getFiles", cmdGetFiles);
            cmdGetSessions = c.optInt("getSessions", cmdGetSessions);
            cmdGetSSN = c.optInt("getSSN", cmdGetSSN);
            cmdFinishFile = c.optInt("finishFile", cmdFinishFile);
            cmdStartRecord = c.optInt("startRecord", cmdStartRecord);
            cmdPauseRecord = c.optInt("pauseRecord", cmdPauseRecord);
            cmdStopRecord = c.optInt("stopRecord", cmdStopRecord);
            cmdQueryParts = c.optInt("queryParts", cmdQueryParts);
            cmdGetUserSet = c.optInt("getUserSet", cmdGetUserSet);
            cmdSetLedHide = c.optInt("setLedHide", cmdSetLedHide);
            cmdGetFreeSize = c.optInt("getFreeSize", cmdGetFreeSize);
        }
        JSONObject r = j.optJSONObject("responses");
        if (r != null) respCapacitiesReq = r.optInt("capacitiesReq", respCapacitiesReq);
        capacitiesTag = j.optString("capacitiesTag", capacitiesTag);
        JSONObject p = j.optJSONObject("parse");
        if (p != null) {
            org.json.JSONArray sa = p.optJSONArray("sessionStrides");
            if (sa != null && sa.length() > 0) {
                sessionStrides = new int[sa.length()];
                for (int i = 0; i < sa.length(); i++) sessionStrides[i] = sa.optInt(i);
            }
            sessionLimit12 = p.optInt("sessionLimit12", sessionLimit12);
            sessionLimit8 = p.optInt("sessionLimit8", sessionLimit8);
            sessionLimit4 = p.optInt("sessionLimit4", sessionLimit4);
            fileStep = p.optInt("fileStep", fileStep);
            fileLimit = p.optInt("fileLimit", fileLimit);
        }
        JSONObject a = j.optJSONObject("audio");
        if (a != null) {
            celtMode = a.optInt("celtMode", celtMode);
            celtModeFallback = a.optInt("celtModeFallback", celtModeFallback);
            sampleRate = a.optInt("sampleRate", sampleRate);
            framePairBytes = a.optInt("framePairBytes", framePairBytes);
            minMtu = a.optInt("minMtu", minMtu);
        }
        JSONObject m = j.optJSONObject("model");
        if (m != null) {
            modelDir = m.optString("dir", modelDir);
            modelFile = m.optString("file", modelFile);
            modelTokens = m.optString("tokens", modelTokens);
            modelMinBytes = m.optLong("minBytes", modelMinBytes);
            modelVersion = m.optString("version", modelVersion);
        }
    }

    private static UUID uOpt(JSONObject j, String key, UUID fallback) {
        String s = j.optString(key, null);
        if (s == null || s.length() < 4) return fallback;
        // 档案里写短形式 "1910"，补全成标准 128 位 UUID
        if (s.length() == 4) s = "0000" + s + "-0000-1000-8000-00805f9b34fb";
        try { return UUID.fromString(s); } catch (Throwable t) { return fallback; }
    }

    private static UUID u(String short16) {
        return UUID.fromString("0000" + short16 + "-0000-1000-8000-00805f9b34fb");
    }

    // ---------- Getter ----------
    public String id() { return id; }
    public String name() { return name; }
    public List<String> namePrefixes() { return namePrefixes; }

    public UUID svcMain() { return uuidServiceMain; }
    public UUID chrWrite() { return uuidWrite; }
    public UUID chrIndicate() { return uuidIndicate; }
    public UUID svcBattery() { return uuidServiceBattery; }
    public UUID chrBattery() { return uuidBattery; }
    public UUID svcData() { return uuidServiceData; }
    public UUID chrData() { return uuidData; }
    public UUID chrCccd() { return uuidCccd; }

    public int cmdCapacities() { return cmdCapacities; }
    public int cmdDownloadFile() { return cmdDownloadFile; }
    public int cmdDownloadStop() { return cmdDownloadStop; }
    public int cmdGetFiles() { return cmdGetFiles; }
    public int cmdGetSessions() { return cmdGetSessions; }
    public int cmdGetSSN() { return cmdGetSSN; }
    public int cmdFinishFile() { return cmdFinishFile; }
    public int cmdStartRecord() { return cmdStartRecord; }
    public int cmdPauseRecord() { return cmdPauseRecord; }
    public int cmdStopRecord() { return cmdStopRecord; }
    public int cmdQueryParts() { return cmdQueryParts; }
    public int cmdGetUserSet() { return cmdGetUserSet; }
    public int cmdSetLedHide() { return cmdSetLedHide; }
    public int cmdGetFreeSize() { return cmdGetFreeSize; }
    public int respCapacitiesReq() { return respCapacitiesReq; }
    public String capacitiesTag() { return capacitiesTag; }

    public int[] sessionStrides() { return sessionStrides; }
    public int sessionLimit12() { return sessionLimit12; }
    public int sessionLimit8() { return sessionLimit8; }
    public int sessionLimit4() { return sessionLimit4; }
    public int fileStep() { return fileStep; }
    public int fileLimit() { return fileLimit; }

    public int celtMode() { return celtMode; }
    public int celtModeFallback() { return celtModeFallback; }
    public int sampleRate() { return sampleRate; }
    public int framePairBytes() { return framePairBytes; }
    public int minMtu() { return minMtu; }

    public String modelDir() { return modelDir; }
    public String modelFile() { return modelFile; }
    public String modelTokens() { return modelTokens; }
    public long modelMinBytes() { return modelMinBytes; }
    public String modelVersion() { return modelVersion; }
}
