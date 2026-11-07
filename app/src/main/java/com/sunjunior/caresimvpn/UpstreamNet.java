package com.sunjunior.caresimvpn;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

/**
 * 选择代理链路的出口网络。
 *
 * <p>ROM 的 VPN 是全局的（没有 uidrange），relay / TLS 适配器 / 探活 socket 必须显式绑定到
 * 一个“真实”的上网网络，否则会被自己的 tun 套住形成回环。
 *
 * <p>优先级：WiFi → 以太网(eth0) → 蜂窝 → 其它可用网络；同类型中优先已通过系统验证的网络，
 * 避免连上无外网的热点后把链路带偏。
 */
final class UpstreamNet {
    private UpstreamNet() {}

    static Network pick(Context ctx) {
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return null;
            return pick(cm);
        } catch (Throwable ignored) {
            return null;
        }
    }

    static Network pick(ConnectivityManager cm) {
        if (cm == null) return null;
        Network wifi = null, wifiAny = null;
        Network eth = null, ethAny = null;
        Network cell = null, cellAny = null;
        Network other = null, otherAny = null;

        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities c = cm.getNetworkCapabilities(n);
            if (c == null) continue;
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
            if (!c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;

            boolean validated = c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                if (validated && wifi == null) wifi = n;
                if (wifiAny == null) wifiAny = n;
            } else if (c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                if (validated && eth == null) eth = n;
                if (ethAny == null) ethAny = n;
            } else if (c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                if (validated && cell == null) cell = n;
                if (cellAny == null) cellAny = n;
            } else {
                if (validated && other == null) other = n;
                if (otherAny == null) otherAny = n;
            }
        }

        if (wifi != null) return wifi;
        if (eth != null) return eth;
        if (cell != null) return cell;
        if (other != null) return other;

        /* 没有网络被系统标记为已验证时，退化为“只要能上网就用” */
        if (wifiAny != null) return wifiAny;
        if (ethAny != null) return ethAny;
        if (cellAny != null) return cellAny;
        return otherAny;
    }

    /** 仅用于日志，标明当前出口类型。 */
    static String typeOf(Context ctx, Network net) {
        if (net == null) return "none";
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkCapabilities c = cm == null ? null : cm.getNetworkCapabilities(net);
            if (c == null) return "unknown";
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "wifi";
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "ethernet";
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return "cellular";
            return "other";
        } catch (Throwable ignored) {
            return "unknown";
        }
    }
}
