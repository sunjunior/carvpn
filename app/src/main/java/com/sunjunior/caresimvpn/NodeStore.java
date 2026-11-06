package com.sunjunior.caresimvpn;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * 多节点存储：每行一条 {@code name\thost\tport\tuser\tpass}，字段转义。
 * 与旧版单节点 {@code node_prefs} 分开存放（{@code node_store}），便于迁移与回滚。
 * 编解码与"最快节点"选择均为纯函数，可在 JVM 单测中覆盖。
 */
public final class NodeStore {
    private static final String PREFS = "node_store";
    private static final String K_NODES = "nodes";
    private static final String K_ACTIVE = "active";
    private static final String K_AUTO = "auto";

    private NodeStore() {}

    public static final class Node {
        public String name;
        public String host;
        public int port;
        public String username;
        public String password;
        /** true = 节点是 HTTPS 代理（TLS 上的 HTTP CONNECT），relay 上游需经 TlsUpstream */
        public boolean tls;
        /** 仅解析订阅时使用，不持久化 */
        public String type;

        public Node() {}

        public Node(String name, String host, int port, String username, String password) {
            this.name = name == null ? "" : name;
            this.host = host == null ? "" : host;
            this.port = port;
            this.username = username == null ? "" : username;
            this.password = password == null ? "" : password;
        }

        public static Node fromConfig(NodeConfig c, String name) {
            return new Node(name, c.host, c.port, c.username, c.password);
        }

        public NodeConfig toConfig() {
            return NodeConfig.fromFields(host, String.valueOf(port), username, password);
        }

        public String key() {
            return host + ":" + port;
        }

        public String label() {
            String n = name == null ? "" : name.trim();
            String base = n.isEmpty() ? key() : n;
            return base + (tls ? " · TLS" : " · 明文");
        }

        public boolean isComplete() {
            return host != null && !host.trim().isEmpty() && port >= 1 && port <= 65535;
        }
    }

    /* ---------- 纯逻辑（可单测） ---------- */

    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': b.append("\\\\"); break;
                case '\t': b.append("\\t"); break;
                case '\n': b.append("\\n"); break;
                case '\r': break;
                default: b.append(c);
            }
        }
        return b.toString();
    }

    private static String unesc(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                switch (n) {
                    case 't': b.append('\t'); break;
                    case 'n': b.append('\n'); break;
                    case '\\': b.append('\\'); break;
                    default: b.append(n);
                }
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }

    public static String encode(List<Node> nodes) {
        StringBuilder b = new StringBuilder();
        if (nodes == null) return "";
        for (Node n : nodes) {
            if (n == null || !n.isComplete()) continue;
            b.append(esc(n.name)).append('\t')
             .append(esc(n.host)).append('\t')
             .append(n.port).append('\t')
             .append(esc(n.username)).append('\t')
             .append(esc(n.password)).append('\t')
             .append(n.tls ? "1" : "0").append('\n');
        }
        return b.toString();
    }

    public static List<Node> decode(String raw) {
        List<Node> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String line : raw.split("\n")) {
            if (line.isEmpty()) continue;
            String[] f = line.split("\t", -1);
            if (f.length < 3) continue;
            int port;
            try {
                port = Integer.parseInt(f[2].trim());
            } catch (NumberFormatException e) {
                continue;
            }
            if (port < 1 || port > 65535) continue;
            Node n = new Node(unesc(f[0]), unesc(f[1]), port,
                    f.length > 3 ? unesc(f[3]) : "",
                    f.length > 4 ? unesc(f[4]) : "");
            n.tls = f.length > 5 && "1".equals(f[5].trim());
            if (n.isComplete()) out.add(n);
        }
        return out;
    }

    /** 返回最小的正时延下标；全部失败返回 -1。 */
    public static int fastestIndex(long[] latencies) {
        int idx = -1;
        long best = Long.MAX_VALUE;
        if (latencies == null) return -1;
        for (int i = 0; i < latencies.length; i++) {
            if (latencies[i] > 0 && latencies[i] < best) {
                best = latencies[i];
                idx = i;
            }
        }
        return idx;
    }

    /** 从 current 之后开始找第一个可用节点（循环一圈）；没有则 null。 */
    public static Node nextOf(List<Node> nodes, Node current) {
        if (nodes == null || nodes.isEmpty()) return null;
        int n = nodes.size();
        int start = 0;
        if (current != null) {
            for (int i = 0; i < n; i++) {
                if (nodes.get(i).key().equals(current.key())) {
                    start = (i + 1) % n;
                    break;
                }
            }
        }
        for (int k = 0; k < n; k++) {
            Node c = nodes.get((start + k) % n);
            if (current == null || !c.key().equals(current.key()) || n == 1) {
                return c;
            }
        }
        return null;
    }

    /* ---------- 持久化 ---------- */

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static List<Node> load(Context ctx) {
        return decode(prefs(ctx).getString(K_NODES, ""));
    }

    public static void save(Context ctx, List<Node> nodes) {
        prefs(ctx).edit().putString(K_NODES, encode(nodes)).apply();
    }

    public static int getActive(Context ctx) {
        return prefs(ctx).getInt(K_ACTIVE, 0);
    }

    public static void setActive(Context ctx, int index) {
        prefs(ctx).edit().putInt(K_ACTIVE, index < 0 ? 0 : index).apply();
    }

    public static boolean isAuto(Context ctx) {
        return prefs(ctx).getBoolean(K_AUTO, true);
    }

    public static void setAuto(Context ctx, boolean auto) {
        prefs(ctx).edit().putBoolean(K_AUTO, auto).apply();
    }

    public static String getSubUrl(Context ctx) {
        return prefs(ctx).getString("sub_url", "");
    }

    public static void setSubUrl(Context ctx, String url) {
        prefs(ctx).edit().putString("sub_url", url == null ? "" : url).apply();
    }

    /** 旧版单节点配置迁移到列表（仅当列表为空且旧配置完整）。 */
    public static List<Node> loadOrMigrate(Context ctx) {
        List<Node> nodes = load(ctx);
        if (nodes.isEmpty()) {
            NodeConfig legacy = NodeConfig.load(ctx);
            if (legacy.isComplete()) {
                nodes.add(Node.fromConfig(legacy, legacy.host));
                save(ctx, nodes);
            }
        }
        return nodes;
    }
}
