package com.c1local.app;

/**
 * C1 私有 .avc 音频的 CELT 解码器封装。
 *
 * so 内 JNI 符号绑定在 com.airoha.celt2chapi.AirohaCeltApi，
 * 这里做一层委托，业务代码调用方式保持不变。
 * 注意：so 只有 32 位版本，App 需以 armeabi-v7a 模式运行。
 */
public class AirohaCeltApi {
    private final com.airoha.celt2chapi.AirohaCeltApi bridge =
            new com.airoha.celt2chapi.AirohaCeltApi();

    public int celtDecodeInit(int mode) {
        return bridge.celtDecodeInit(mode);
    }

    public short[] celtDecodeProc(byte[] data, int len, int decId, int mode) {
        return bridge.celtDecodeProc(data, len, decId, mode);
    }
}
