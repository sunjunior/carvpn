package com.sunjunior.caresimvpn;

import android.content.Context;
import android.content.SharedPreferences;

/** 节点参数。持久化用 SharedPreferences 独立 key；纯逻辑(parsePort/fromFields/isComplete) JVM 可测。 */
public final class NodeConfig {
    public final String host;
    public final int port;
    public final String username;
    public final String password;
    /** 端口是显式填写的（false）还是空缺时默认的 1080（true，视为未配置好） */
    public final boolean portDefaulted;

    public NodeConfig(String host, int port, String username, String password) {
        this(host, port, username, password, false);
    }

    private NodeConfig(String host, int port, String username, String password, boolean portDefaulted) {
        this.host = host == null ? "" : host;
        this.port = port;
        this.username = username == null ? "" : username;
        this.password = password == null ? "" : password;
        this.portDefaulted = portDefaulted;
    }

    static int parsePort(String s) {
        if (s == null) return 1080;
        try {
            int v = Integer.parseInt(s.trim());
            return (v >= 1 && v <= 65535) ? v : 1080;
        } catch (NumberFormatException e) { return 1080; }
    }

    public static NodeConfig fromFields(String host, String portStr, String user, String pass) {
        String h = host == null ? "" : host.trim();
        String u = user == null ? "" : user.trim();
        String p = pass == null ? "" : pass.trim();
        int port; boolean explicit;
        try {
            int v = Integer.parseInt(portStr == null ? "" : portStr.trim());
            explicit = v >= 1 && v <= 65535;
            port = explicit ? v : 1080;
        } catch (NumberFormatException e) {
            explicit = false; port = 1080;
        }
        return new NodeConfig(h, port, u, p, !explicit);
    }

    public boolean isComplete() {
        return !host.isEmpty() && !portDefaulted && port >= 1 && port <= 65535;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("node_prefs", Context.MODE_PRIVATE);
    }

    public void save(Context ctx) {
        prefs(ctx).edit()
                .putString("host", host)
                .putString("port", String.valueOf(port))
                .putString("username", username)
                .putString("password", password)
                .apply();
    }

    public static NodeConfig load(Context ctx) {
        SharedPreferences p = prefs(ctx);
        return fromFields(p.getString("host", ""), p.getString("port", ""),
                p.getString("username", ""), p.getString("password", ""));
    }
}
