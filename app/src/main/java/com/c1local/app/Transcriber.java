package com.c1local.app;

import android.content.res.AssetManager;

import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizerResult;
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig;
import com.k2fsa.sherpa.onnx.OfflineStream;

/**
 * 本地 SenseVoice 语音识别（sherpa-onnx）。全程离线，音频不出手机。
 */
public class Transcriber {
    private final OfflineRecognizer recognizer;

    /**
     * 注意：传 assets=null 让 sherpa-onnx 走 newFromFile（文件系统路径）。
     * 若传非 null 的 AssetManager，它会走 newFromAsset，与我们的文件路径冲突而加载失败。
     */
    public Transcriber(AssetManager assets, String modelDir) {
        OfflineSenseVoiceModelConfig sv = new OfflineSenseVoiceModelConfig();
        sv.setModel(modelDir + "/model.int8.onnx");
        sv.setLanguage("auto");             // 自动检测中英日韩粤
        sv.setUseInverseTextNormalization(true);

        OfflineModelConfig model = new OfflineModelConfig();
        model.setSenseVoice(sv);
        model.setTokens(modelDir + "/tokens.txt");
        model.setNumThreads(2);
        model.setProvider("cpu");

        FeatureConfig feat = new FeatureConfig();
        feat.setSampleRate(16000);
        feat.setFeatureDim(80);

        OfflineRecognizerConfig cfg = new OfflineRecognizerConfig();
        cfg.setFeatConfig(feat);
        cfg.setModelConfig(model);

        // 传 null → newFromFile，模型从 modelDir 的绝对路径读取
        this.recognizer = new OfflineRecognizer(null, cfg);
    }

    /** 识别 float 样本（16kHz 单声道），返回带语言/情感标签的文本 */
    public Result recognize(float[] samples) {
        if (samples == null || samples.length == 0) {
            return new Result("", "", "", "");
        }
        OfflineStream stream = recognizer.createStream();
        stream.acceptWaveform(samples, 16000);
        recognizer.decode(stream);
        OfflineRecognizerResult r = recognizer.getResult(stream);
        stream.release();
        return new Result(r.getText(), r.getLang(), r.getEmotion(), r.getEvent());
    }

    public void release() {
        if (recognizer != null) recognizer.release();
    }

    public static class Result {
        public final String text;
        public final String lang;
        public final String emotion;
        public final String event;
        Result(String text, String lang, String emotion, String event) {
            this.text = text;
            this.lang = lang;
            this.emotion = emotion;
            this.event = event;
        }
    }
}
