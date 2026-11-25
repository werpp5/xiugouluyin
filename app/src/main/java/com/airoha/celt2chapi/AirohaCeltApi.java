package com.airoha.celt2chapi;

/**
 * JNI 桥接类。
 *
 * libairoha-celtapi.so 中导出的符号是
 *   Java_com_airoha_celt2chapi_AirohaCeltApi_celtDecodeInit
 *   Java_com_airoha_celt2chapi_AirohaCeltApi_celtDecodeProc
 * 因此 native 方法必须声明在 com.airoha.celt2chapi.AirohaCeltApi 下，
 * 否则 JNI 按符号名查找时会找不到实现，抛 UnsatisfiedLinkError。
 */
public class AirohaCeltApi {
    static {
        System.loadLibrary("airoha-celtapi");
    }

    public native int celtDecodeInit(int mode);

    public native short[] celtDecodeProc(byte[] data, int len, int decId, int mode);
}
