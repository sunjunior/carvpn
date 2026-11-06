package com.sunjunior.caresimvpn;

/**
 * hev-socks5-tunnel 官方 JNI 绑定（见 docs/VENDORED.md Q1）。
 * 类名/方法名必须与 Application.mk 中 PKGNAME/CLSNAME 编译期锚定一致：
 * PKGNAME=com/sunjunior/caresimvpn, CLSNAME=CarVpnTunnel。
 */
public final class CarVpnTunnel {
    static {
        System.loadLibrary("hev-socks5-tunnel");
    }

    private CarVpnTunnel() {}

    static native boolean TProxyStartService(String config_path, int fd);

    static native boolean TProxyStopService();

    static native boolean TProxyIsRunning();

    static native long[] TProxyGetStats();
}
