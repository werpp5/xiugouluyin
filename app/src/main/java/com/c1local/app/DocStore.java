package com.c1local.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;

/**
 * 历史文稿持久化：每条转写结果存成一条记录，写到本地 JSON 文件。
 * 全程离线，文稿不出手机。删除文稿不影响对应音频，反之亦然。
 *
 * text = 当前显示/编辑稿（用户可手改）
 * rawText = 转写原文（只读，用于"恢复转写原文"）
 * source = 转写来源："侧载" / "云端"
 */
public class DocStore {

    public static class Doc {
        public String id;        // 唯一标识：sid_fid
        public String title;     // 显示名称（默认录音时间，可重命名）
        public String text;      // 当前显示稿（可编辑）
        public String rawText;   // 转写原文（只读）
        public String source;    // 转写来源：侧载 / 云端
        public String audio;     // 对应 wav 文件名（C1_sid_fid.wav）
        public int durMs;        // 时长（毫秒）
        public long savedAt;     // 保存时间戳（毫秒）
        public boolean exported; // 是否已导出过 txt

        public Doc() {}
        public Doc(String id, String title, String text, String audio, int durMs) {
            this(id, title, text, audio, durMs, "侧载");
        }
        public Doc(String id, String title, String text, String audio, int durMs, String source) {
            this.id = id;
            this.title = title;
            this.text = text;
            this.rawText = text;
            this.source = source == null || source.isEmpty() ? "侧载" : source;
            this.audio = audio;
            this.durMs = durMs;
            this.savedAt = System.currentTimeMillis();
            this.exported = false;
        }

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id);
                o.put("title", title);
                o.put("text", text);
                o.put("raw", rawText == null ? text : rawText);
                o.put("src", source == null ? "侧载" : source);
                o.put("audio", audio);
                o.put("dur", durMs);
                o.put("ts", savedAt);
                o.put("exp", exported);
            } catch (Exception ignored) {}
            return o;
        }

        static Doc fromJson(JSONObject o) {
            Doc d = new Doc();
            try { d.id = o.optString("id", ""); } catch (Exception ignored) {}
            try { d.title = o.optString("title", ""); } catch (Exception ignored) {}
            try { d.text = o.optString("text", ""); } catch (Exception ignored) {}
            // 向后兼容：老文稿没有 raw 字段，原文就等于 text
            String raw = o.optString("raw", "");
            d.rawText = raw.isEmpty() ? d.text : raw;
            d.source = o.optString("src", "侧载");
            if (d.source.isEmpty()) d.source = "侧载";
            try { d.audio = o.optString("audio", ""); } catch (Exception ignored) {}
            d.durMs = o.optInt("dur", 0);
            d.savedAt = o.optLong("ts", 0);
            d.exported = o.optBoolean("exp", false);
            return d;
        }

        public boolean isEdited() { return !text.equals(rawText); }
    }

    private final File file;
    private final ArrayList<Doc> docs = new ArrayList<>();

    public DocStore(Context ctx) {
        File dir = new File(ctx.getExternalFilesDir(null), "docs");
        if (!dir.exists()) dir.mkdirs();
        file = new File(dir, "history_docs.json");
        load();
    }

    private void load() {
        docs.clear();
        if (!file.exists() || file.length() == 0) return;
        try (FileReader r = new FileReader(file)) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[8192];
            int n;
            while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
            JSONArray arr = new JSONArray(sb.toString());
            for (int i = 0; i < arr.length(); i++) {
                Doc d = Doc.fromJson(arr.getJSONObject(i));
                if (d.id != null && !d.id.isEmpty()) docs.add(d);
            }
        } catch (Exception ignored) {}
    }

    private void save() {
        try {
            JSONArray arr = new JSONArray();
            for (Doc d : docs) arr.put(d.toJson());
            try (FileWriter w = new FileWriter(file)) { w.write(arr.toString()); }
        } catch (Exception ignored) {}
    }

    /**
     * 存入一次转写结果。保护用户手改：
     * - 同一段录音重复转写时，若已有文稿且用户改过 text，则保留 text 不动，只更新 rawText/source/时长；
     * - 只有 text 为空（或文稿不存在）时才把新结果写入 text。
     * 最新的排最前。
     */
    public void put(Doc doc) {
        Doc old = get(doc.id);
        if (old != null) {
            boolean userEdited = old.text != null && !old.text.isEmpty() && old.isEdited();
            if (userEdited) {
                old.rawText = doc.rawText;
                old.source = doc.source;
                old.durMs = doc.durMs;
                old.savedAt = System.currentTimeMillis();
                docs.remove(old);
                docs.add(0, old);
                save();
                return;
            }
        }
        docs.removeIf(d -> d.id.equals(doc.id));
        docs.add(0, doc);
        save();
    }

    public ArrayList<Doc> all() { return docs; }

    public Doc get(String id) {
        for (Doc d : docs) if (d.id.equals(id)) return d;
        return null;
    }

    /** 编辑页保存当前文本 */
    public void saveText(String id, String text) {
        Doc d = get(id);
        if (d != null) { d.text = text == null ? "" : text; save(); }
    }

    /** 恢复转写原文 */
    public void restoreRaw(String id) {
        Doc d = get(id);
        if (d != null) { d.text = d.rawText == null ? "" : d.rawText; save(); }
    }

    public void remove(String id) {
        docs.removeIf(d -> d.id.equals(id));
        save();
    }

    public void rename(String id, String title) {
        Doc d = get(id);
        if (d != null) { d.title = title; save(); }
    }

    public void markExported(String id) {
        Doc d = get(id);
        if (d != null) { d.exported = true; save(); }
    }

    public int size() { return docs.size(); }
}
