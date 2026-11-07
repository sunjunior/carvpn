package com.sunjunior.caresimvpn;

import android.net.Network;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 订阅导入：拉取 Clash YAML，解析其中 type=http 的节点（relay 的上游只支持 HTTP CONNECT）。
 * 解析部分为纯函数，可在 JVM 单测覆盖。
 */
final class SubImporter {
    private SubImporter() {}

    /** 通过指定出口网络（WiFi/eth0）拉取订阅，避免被自身 VPN 套住。 */
    static String fetch(String url, Network net, int timeoutMs, android.content.Context ctx) throws IOException {
        return HttpFetcher.get(url, net, timeoutMs, ctx);
    }

    static final class Parsed {
        final List<NodeStore.Node> nodes = new ArrayList<>();
        int skippedOtherType = 0;
    }

    /** 极简 Clash YAML 解析：只认 proxies: 下的 name/type/server/port/username/password。 */
    static Parsed parseClash(String text) {
        Parsed out = new Parsed();
        if (text == null) return out;
        String[] lines = text.replace("\r", "").split("\n");
        boolean inProxies = false;
        NodeStore.Node cur = null;
        boolean curIsHttp = false;

        for (String raw : lines) {
            String t = raw.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            int indent = raw.indexOf(t.charAt(0));
            if (!inProxies) {
                if (t.equals("proxies:")) inProxies = true;
                continue;
            }
            if (indent == 0 && !t.startsWith("-")) break;   // proxies 段结束

            if (t.startsWith("- ")) {
                if (cur != null && curIsHttp && cur.isComplete()) out.nodes.add(cur);
                else if (cur != null && !curIsHttp) out.skippedOtherType++;
                cur = new NodeStore.Node();
                curIsHttp = false;
                String body = t.substring(2).trim();
                if (body.startsWith("{") && body.endsWith("}")) {
                    String[] parts = body.substring(1, body.length() - 1).split(",");
                    for (String p : parts) kv(p.trim(), cur);
                } else {
                    kv(body, cur);
                }
                if (cur.type != null) curIsHttp = "http".equalsIgnoreCase(cur.type);
            } else if (cur != null) {
                kv(t, cur);
                if (cur.type != null) curIsHttp = "http".equalsIgnoreCase(cur.type);
            }
        }
        if (cur != null) {
            if (curIsHttp && cur.isComplete()) out.nodes.add(cur);
            else if (!curIsHttp) out.skippedOtherType++;
        }
        /* 去重（同 host:port）并清理 name */
        List<NodeStore.Node> uniq = new ArrayList<>();
        for (NodeStore.Node n : out.nodes) {
            boolean dup = false;
            for (NodeStore.Node x : uniq) {
                if (x.key().equals(n.key())) { dup = true; break; }
            }
            if (!dup) {
                if (n.name == null || n.name.trim().isEmpty()) n.name = n.key();
                uniq.add(n);
            }
        }
        out.nodes.clear();
        out.nodes.addAll(uniq);
        return out;
    }

    /** Node 复用一个临时字段承载 type（解析用）。 */
    private static void kv(String line, NodeStore.Node n) {
        int c = line.indexOf(':');
        if (c <= 0) return;
        String k = line.substring(0, c).trim();
        String v = line.substring(c + 1).trim();
        if (v.endsWith(" #")) v = v.substring(0, v.length() - 2).trim();
        v = unquote(v);
        if ("name".equals(k)) n.name = v;
        else if ("type".equals(k)) n.type = v;
        else if ("server".equals(k)) n.host = v;
        else if ("port".equals(k)) {
            try { n.port = Integer.parseInt(v); } catch (NumberFormatException ignored) { }
        } else if ("username".equals(k)) n.username = v;
        else if ("password".equals(k)) n.password = v;
        else if ("tls".equals(k)) n.tls = "true".equalsIgnoreCase(v);
    }

    private static String unquote(String v) {
        if (v.length() >= 2) {
            char a = v.charAt(0), b = v.charAt(v.length() - 1);
            if ((a == '"' && b == '"') || (a == '\'' && b == '\'')) {
                String s = v.substring(1, v.length() - 1);
                return s.replace("\\\"", "\"").replace("\\\\", "\\");
            }
        }
        return v;
    }
}
