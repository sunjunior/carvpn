package com.sunjunior.caresimvpn;

import android.content.Context;
import android.net.Network;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;

import javax.net.ssl.SSLSocket;

/**
 * 节点探活：TCP 建连 + HTTP CONNECT 探测（与 relay 的实际握手一致）。
 * 节点标记 tls 时先做 TLS 握手，再在其上发 CONNECT（与 TlsUpstream 一致）。
 */
final class NodeProbe {
    private static final String PROBE_TARGET = "qq.com:80";

    /** 由 EsimVpnService 注入：探活 socket 绑定到真实出口网络（WiFi/eth0），避免被自身 VPN 套住 */
    static volatile Network bindNetwork;
    /** 由 EsimVpnService 注入：TLS 握手需要证书上下文 */
    static volatile Context appContext;

    private NodeProbe() {}

    private static void bindUpstream(Socket s) {
        Network n = bindNetwork;
        if (n == null) return;
        try {
            n.bindSocket(s);
        } catch (Throwable ignored) { }
    }

    /** 只测 TCP 建连时延；失败返回 -1。 */
    static long tcpLatency(String host, int port, int timeoutMs) {
        if (host == null || host.trim().isEmpty() || port < 1 || port > 65535) return -1;
        long t0 = System.currentTimeMillis();
        Socket s = new Socket();
        try {
            bindUpstream(s);
            s.connect(new InetSocketAddress(host.trim(), port), timeoutMs);
            return System.currentTimeMillis() - t0;
        } catch (Exception e) {
            return -1;
        } finally {
            try { s.close(); } catch (IOException ignored) { }
        }
    }

    /** 完整探活：CONNECT qq.com:80 必须回 200。返回时延，失败 -1。 */
    static long probe(NodeStore.Node n, int timeoutMs) {
        if (n == null || !n.isComplete()) return -1;
        long t0 = System.nanoTime();
        Socket s = new Socket();
        SSLSocket tls = null;
        try {
            bindUpstream(s);
            s.connect(new InetSocketAddress(n.host.trim(), n.port), timeoutMs);
            int remain = remainingMs(t0, timeoutMs);
            if (remain <= 0) return -1;
            s.setSoTimeout(remain);
            OutputStream os;
            if (n.tls) {
                Context ctx = appContext;
                if (ctx == null) return -1;
                tls = (SSLSocket) HttpFetcher.sslContext(ctx)
                        .getSocketFactory().createSocket(s, n.host.trim(), n.port, true);
                tls.setUseClientMode(true);
                tls.setSoTimeout(remain);
                tls.startHandshake();
                os = tls.getOutputStream();
                remain = remainingMs(t0, timeoutMs);
                if (remain <= 0) return -1;
                tls.setSoTimeout(remain);
            } else {
                os = s.getOutputStream();
            }
            StringBuilder req = new StringBuilder();
            req.append("CONNECT ").append(PROBE_TARGET).append(" HTTP/1.1\r\n")
               .append("Host: ").append(PROBE_TARGET).append("\r\n");
            String u = n.username == null ? "" : n.username.trim();
            String p = n.password == null ? "" : n.password;
            if (!u.isEmpty()) {
                req.append("Proxy-Authorization: Basic ")
                   .append(base64(u + ":" + p)).append("\r\n");
            }
            req.append("Proxy-Connection: keep-alive\r\n\r\n");

            os.write(req.toString().getBytes("ISO-8859-1"));
            os.flush();

            String status = readLine(n.tls ? tls.getInputStream() : s.getInputStream());
            if (status != null && status.startsWith("HTTP/") && status.contains(" 200")) {
                return (System.nanoTime() - t0) / 1000000L;
            }
            Log.d("carvpn", "probe bad status " + n.label() + ": " + status);
            return -1;
        } catch (Exception e) {
            Log.d("carvpn", "probe fail " + n.label() + ": " + e);
            return -1;
        } finally {
            if (tls != null) { try { tls.close(); } catch (IOException ignored) { } }
            try { s.close(); } catch (IOException ignored) { }
        }
    }

    /**
     * 自动选路：先用短超时 TCP 预筛全部节点，再对最快的若干节点做
     * 「TLS 握手 + CONNECT 200」完整探活，避免一次性发起几十个 TLS 握手把链路打爆。
     * 返回各节点时延（-1 表示未通过或未探测）。
     */
    static long[] probeAll(final List<NodeStore.Node> nodes, final int probeTimeoutMs) {
        final int n = nodes == null ? 0 : nodes.size();
        final long[] lat = new long[n];
        for (int i = 0; i < n; i++) lat[i] = -1;
        if (n == 0) return lat;

        /* 1) TCP 预筛：并发但开销很小 */
        final long[] tcp = new long[n];
        for (int i = 0; i < n; i++) tcp[i] = -1;
        Thread[] ts = new Thread[n];
        for (int i = 0; i < n; i++) {
            final int k = i;
            final NodeStore.Node node = nodes.get(k);
            ts[i] = new Thread(new Runnable() {
                @Override public void run() {
                    tcp[k] = tcpLatency(node.host, node.port, 2500);
                }
            }, "tcp-" + i);
        }
        for (int i = 0; i < n; i++) ts[i].start();
        joinAll(ts, 3000);

        /* 2) 按 TCP 时延排序（不可达的排最后） */
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        java.util.Arrays.sort(order, new java.util.Comparator<Integer>() {
            @Override public int compare(Integer a, Integer b) {
                long x = tcp[a], y = tcp[b];
                if (x < 0) return (y < 0) ? 0 : 1;
                if (y < 0) return -1;
                return Long.compare(x, y);
            }
        });

        /* 3) 分批做完整探活：本车链路对部分地区节点会丢弃 TLS，
         *    因此不能只看 TCP 时延；至少拿到 2 个成功节点或超出总预算才停止，
         *    最后仍按时延取最快，避免“首批里有个慢节点就定案”。 */
        final int batch = 6;
        final long scanStart = System.currentTimeMillis();
        final long scanBudgetMs = 9000;
        int okCount = 0;
        for (int base = 0; base < n; base += batch) {
            if (okCount >= 2) break;
            if (base > 0 && System.currentTimeMillis() - scanStart >= scanBudgetMs) break;
            int cnt = 0;
            Thread[] ps = new Thread[batch];
            for (int j = base; j < Math.min(base + batch, n); j++) {
                final int k = order[j];
                if (tcp[k] < 0) continue;
                ps[cnt] = new Thread(new Runnable() {
                    @Override public void run() {
                        lat[k] = probe(nodes.get(k), probeTimeoutMs);
                    }
                }, "probe-" + k);
                ps[cnt].start();
                cnt++;
            }
            if (cnt == 0) break;
            joinAll(ps, probeTimeoutMs + 800L);
            for (int j = base; j < Math.min(base + batch, n); j++) {
                if (lat[order[j]] > 0) okCount++;
            }
        }

        for (int i = 0; i < n; i++) {
            Log.i("carvpn", "probe #" + i + " " + nodes.get(i).label()
                    + " tcp=" + tcp[i] + "ms full=" + lat[i] + "ms");
        }
        return lat;
    }

    /** 单节点探活共用一份超时预算：连接 + 握手 + 读应答合计不超过 timeoutMs。 */
    private static int remainingMs(long t0, int timeoutMs) {
        long used = (System.nanoTime() - t0) / 1000000L;
        return (int) (timeoutMs - used);
    }

    private static void joinAll(Thread[] ts, long deadlineMs) {
        long deadline = System.currentTimeMillis() + deadlineMs;
        for (Thread t : ts) {
            if (t == null) continue;
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) return;
            try { t.join(left); } catch (InterruptedException ignored) { }
        }
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder b = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.append((char) c);
            if (b.length() > 512) break;
        }
        return b.length() == 0 ? null : b.toString();
    }

    private static final char[] B64 =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    static String base64(String s) {
        byte[] in = s.getBytes(java.nio.charset.Charset.forName("UTF-8"));
        StringBuilder out = new StringBuilder((in.length + 2) / 3 * 4);
        int i = 0;
        while (i < in.length) {
            int b0 = in[i++] & 0xff;
            int b1 = i < in.length ? in[i++] & 0xff : -1;
            int b2 = i < in.length ? in[i++] & 0xff : -1;
            out.append(B64[b0 >> 2]);
            out.append(B64[((b0 & 0x03) << 4) | (b1 < 0 ? 0 : b1 >> 4)]);
            out.append(b1 < 0 ? '=' : B64[((b1 & 0x0f) << 2) | (b2 < 0 ? 0 : b2 >> 6)]);
            out.append(b2 < 0 ? '=' : B64[b2 & 0x3f]);
        }
        return out.toString();
    }
}
