package com.sunjunior.caresimvpn;

import android.content.Context;
import android.net.Network;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLSocket;

/**
 * 本地 TLS 上游适配器。
 *
 * <p>relay 只会说明文 HTTP CONNECT，而订阅节点是 HTTPS 代理（tls: true）。
 * 本类监听 127.0.0.1 的临时端口，把 relay 的明文连接 1:1 转成：
 * TLS(节点，SNI=节点域名) → 转发 CONNECT → 双向透传。
 *
 * <p>外层 TLS 同时起到加密隧道的作用，链路上的 DPI 看不到内层 SNI。
 * 节点连接必须绑定到 eth0 网络，否则会被自己的 tun 套住形成回环。
 */
final class TlsUpstream {
    private static final String TAG = "carvpn";
    private static final int MAX_CONN = 256;
    private static final int CONNECT_TIMEOUT_MS = 10000;

    private final Context ctx;
    private final Network net;
    private final String host;
    private final int port;

    private ServerSocket server;
    private Thread acceptThread;
    private volatile boolean running;
    private final AtomicInteger conns = new AtomicInteger();

    TlsUpstream(Context ctx, Network net, String host, int port) {
        this.ctx = ctx.getApplicationContext();
        this.net = net;
        this.host = host == null ? "" : host.trim();
        this.port = port;
    }

    /** 启动并返回本地监听端口；失败抛 IOException。 */
    int start() throws IOException {
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 64);
        running = true;
        acceptThread = new Thread(new Runnable() {
            @Override public void run() {
                acceptLoop();
            }
        }, "carvpn-tls-up");
        acceptThread.start();
        Log.i(TAG, "tls upstream 127.0.0.1:" + server.getLocalPort() + " -> " + host + ":" + port);
        return server.getLocalPort();
    }

    void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (Exception ignored) { }
        server = null;
    }

    private void acceptLoop() {
        while (running) {
            Socket c = null;
            try {
                c = server.accept();
            } catch (Exception e) {
                if (!running) return;
                Log.w(TAG, "tls-up accept: " + e);
                continue;
            }
            if (conns.incrementAndGet() > MAX_CONN) {
                conns.decrementAndGet();
                try { c.close(); } catch (Exception ignored) { }
                continue;
            }
            final Socket client = c;
            Thread t = new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        handle(client);
                    } catch (Throwable e) {
                        Log.w(TAG, "tls-up conn: " + e);
                    } finally {
                        try { client.close(); } catch (Exception ignored) { }
                        conns.decrementAndGet();
                    }
                }
            }, "carvpn-tls-up-conn");
            t.setDaemon(true);
            t.start();
        }
    }

    private void handle(Socket client) throws Exception {
        client.setTcpNoDelay(true);
        InputStream cin = client.getInputStream();
        OutputStream cout = client.getOutputStream();

        /* 1) 读出 relay 发来的 CONNECT 请求头（原样转发，认证头已在里面） */
        byte[] req = readHeaders(cin, 8192);
        if (req == null) return;

        /* 2) TLS 连节点；socket 先绑 eth0，再建连，最后套 TLS（带 SNI） */
        Socket raw = new Socket();
        try {
            if (net != null) net.bindSocket(raw);
            raw.connect(new InetSocketAddress(resolve(), port), CONNECT_TIMEOUT_MS);
            raw.setTcpNoDelay(true);
            raw.setSoTimeout(CONNECT_TIMEOUT_MS);

            SSLSocket tls = (SSLSocket) HttpFetcher.sslContext(ctx)
                    .getSocketFactory().createSocket(raw, host, port, true);
            tls.setUseClientMode(true);
            tls.startHandshake();

            /* 3) 转发 CONNECT，读节点应答头（含 200/502 等状态行） */
            OutputStream tos = tls.getOutputStream();
            tos.write(req);
            tos.flush();

            byte[] rep = readHeaders(tls.getInputStream(), 8192);
            if (rep == null) {
                tls.close();
                return;
            }
            cout.write(rep);
            cout.flush();
            if (!isOk(rep)) {
                tls.close();
                return;
            }

            /* 4) 双向透传 */
            tls.setSoTimeout(0);
            raw.setSoTimeout(0);
            ClientToServer c2s = new ClientToServer(cin, tos, tls, client);
            ServerToClient s2c = new ServerToClient(tls.getInputStream(), cout, client, tls);
            c2s.start();
            s2c.start();
            c2s.join();
            s2c.join();
            try { tls.close(); } catch (Exception ignored) { }
        } catch (Throwable e) {
            try { raw.close(); } catch (Exception ignored) { }
            throw e;
        }
    }

    private InetAddress resolve() throws IOException {
        if (net != null) {
            try {
                InetAddress[] all = net.getAllByName(host);
                if (all != null && all.length > 0) return all[0];
            } catch (Throwable ignored) { }
        }
        return InetAddress.getByName(host);
    }

    /** 读到空行为止（含空行）；超过上限或对端关闭返回 null。 */
    private static byte[] readHeaders(InputStream in, int max) throws IOException {
        byte[] buf = new byte[max];
        int n = 0;
        int state = 0;   /* 匹配 \r\n\r\n */
        while (n < max) {
            int b = in.read();
            if (b < 0) return null;
            buf[n++] = (byte) b;
            if ((state == 0 || state == 2) && b == '\r') state++;
            else if ((state == 1 || state == 3) && b == '\n') state++;
            else state = (b == '\r') ? 1 : 0;
            if (state == 4) break;
        }
        if (n == 0) return null;
        byte[] out = new byte[n];
        System.arraycopy(buf, 0, out, 0, n);
        return out;
    }

    private static boolean isOk(byte[] rep) {
        String s = new String(rep, 0, Math.min(rep.length, 16),
                java.nio.charset.Charset.forName("ISO-8859-1"));
        return s.startsWith("HTTP/1.0 200") || s.startsWith("HTTP/1.1 200");
    }

    private static final class ClientToServer extends Thread {
        private final InputStream in;
        private final OutputStream out;
        private final Socket a, b;

        ClientToServer(InputStream in, OutputStream out, Socket a, Socket b) {
            this.in = in; this.out = out; this.a = a; this.b = b;
            setName("carvpn-tls-up-c2s");
            setDaemon(true);
        }

        @Override public void run() {
            byte[] buf = new byte[8192];
            try {
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    out.flush();
                }
            } catch (Exception ignored) {
            } finally {
                try { a.shutdownOutput(); } catch (Exception ignored) { }
                try { if (b != null) b.shutdownOutput(); } catch (Exception ignored) { }
            }
        }
    }

    private static final class ServerToClient extends Thread {
        private final InputStream in;
        private final OutputStream out;
        private final Socket a, b;

        ServerToClient(InputStream in, OutputStream out, Socket a, Socket b) {
            this.in = in; this.out = out; this.a = a; this.b = b;
            setName("carvpn-tls-up-s2c");
            setDaemon(true);
        }

        @Override public void run() {
            byte[] buf = new byte[8192];
            try {
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    out.flush();
                }
            } catch (Exception ignored) {
            } finally {
                try { a.shutdownOutput(); } catch (Exception ignored) { }
                try { if (b != null) b.shutdownOutput(); } catch (Exception ignored) { }
            }
        }
    }
}
