package com.c1local.app;

import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 云端转写：写死兼容 OpenAI /v1/audio/transcriptions 格式。
 * multipart/form-data，字段 file + model，Authorization: Bearer <key>。
 * 返回 JSON 里的 text 字段。仅显式选择云端时才会上传音频。
 */
public class CloudTranscriber {

    public static class Result {
        public final boolean ok;
        public final String text;
        public final String error;
        Result(boolean ok, String text, String error) {
            this.ok = ok; this.text = text; this.error = error;
        }
        static Result success(String text) { return new Result(true, text, null); }
        static Result fail(String error) { return new Result(false, null, error); }
    }

    /** 把用户填的地址规范化成 /v1/audio/transcriptions 全路径 */
    public static String endpointOf(String base) {
        if (base == null) base = "";
        String b = base.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        if (b.isEmpty()) return "";
        if (b.endsWith("/audio/transcriptions")) return b;
        if (b.endsWith("/v1")) return b + "/audio/transcriptions";
        return b + "/v1/audio/transcriptions";
    }

    /**
     * 上传 wav 并取回转写文本。
     * @param base 服务商地址（如 https://api.openai.com/v1）
     */
    public static Result transcribe(String base, String key, String model, File wav) {
        String endpoint = endpointOf(base);
        if (endpoint.isEmpty()) return Result.fail("未填写服务商地址");
        if (key == null || key.trim().isEmpty()) return Result.fail("未填写服务商 key");
        if (model == null || model.trim().isEmpty()) return Result.fail("未填写模型名称");
        if (wav == null || !wav.exists()) return Result.fail("音频文件不存在");

        String boundary = "----C1Local" + UUID.randomUUID().toString().replace("-", "");
        HttpURLConnection conn = null;
        try {
            URL url = new URL(endpoint);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setDoInput(true);
            conn.setUseCaches(false);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(60000);
            conn.setRequestProperty("Authorization", "Bearer " + key.trim());
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            conn.setRequestProperty("Accept", "application/json");

            String mime = "audio/wav";
            String fileName = wav.getName();
            DataOutputStream out = new DataOutputStream(conn.getOutputStream());
            // file 字段
            out.writeBytes("--" + boundary + "\r\n");
            out.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"\r\n");
            out.writeBytes("Content-Type: " + mime + "\r\n\r\n");
            try (FileInputStream in = new FileInputStream(wav)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            out.writeBytes("\r\n");
            // model 字段
            out.writeBytes("--" + boundary + "\r\n");
            out.writeBytes("Content-Disposition: form-data; name=\"model\"\r\n\r\n");
            out.writeBytes(model.trim() + "\r\n");
            out.writeBytes("--" + boundary + "--\r\n");
            out.flush();
            out.close();

            int code = conn.getResponseCode();
            if (code == 401 || code == 403) return Result.fail("鉴权失败（HTTP " + code + "），检查 key");
            if (code < 200 || code >= 300) {
                String err = readBody(conn.getErrorStream());
                return Result.fail("HTTP " + code + "：" + brief(err));
            }
            String body = readBody(conn.getInputStream());
            if (body == null || body.trim().isEmpty()) return Result.fail("返回为空");
            JSONObject o = new JSONObject(body);
            String text = o.optString("text", "");
            if (text.isEmpty()) text = o.optString("transcription", "");
            if (text.isEmpty()) return Result.fail("返回格式不对（无 text 字段）：" + brief(body));
            return Result.success(text.trim());
        } catch (java.net.SocketTimeoutException e) {
            return Result.fail("请求超时");
        } catch (java.net.UnknownHostException e) {
            return Result.fail("地址无法解析，检查网络/地址");
        } catch (Exception e) {
            Log.e("C1APP", "云端转写异常: " + e.getMessage());
            return Result.fail("请求失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String readBody(java.io.InputStream in) {
        if (in == null) return null;
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
        } catch (Exception ignored) {}
        return sb.toString();
    }

    private static String brief(String s) {
        if (s == null) return "";
        return s.length() > 160 ? s.substring(0, 160) + "…" : s;
    }
}
