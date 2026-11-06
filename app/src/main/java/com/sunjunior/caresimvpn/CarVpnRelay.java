package com.sunjunior.caresimvpn;

/**
 * 本地 relay 子进程常量（可执行由 ndkBuild BUILD_EXECUTABLE 产出，
 * 以 libcarvpnrelay.so 名字打进 APK 并解包到 nativeLibraryDir，ProcessBuilder 拉起）。
 */
final class CarVpnRelay {
    static final String EXEC_NAME = "libcarvpnrelay.so";
    static final int PORT = 8101;
    private CarVpnRelay() {}
}
