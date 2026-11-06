package com.sunjunior.caresimvpn;

import android.content.Context;
import android.net.Network;
import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

/**
 * 订阅下载器：车机 eSIM 的 UDP/53 不可用，这里改用 DNS-over-TCP 解析，
 * 再以原始 socket 直连（HTTPS 保留 SNI 与证书/主机名校验）。
 */
final class HttpFetcher {
    private static final String UA = "ClashforWindows/0.20.39";
    private static final String[] RESOLVERS = {"223.5.5.5", "114.114.114.114", "8.8.8.8"};

    private HttpFetcher() {}

    static String get(String url, Network net, int timeoutMs, Context ctx) throws IOException {
        String cur = url;
        for (int redirect = 0; redirect < 5; redirect++) {
            Resp r = request(cur, net, timeoutMs, ctx);
            if (r.status >= 300 && r.status < 400 && r.location != null) {
                cur = new URL(new URL(cur), r.location).toString();
                continue;
            }
            if (r.status != 200) throw new IOException("HTTP " + r.status);
            return r.body;
        }
        throw new IOException("too many redirects");
    }

    private static volatile SSLContext sCtx;

    /** 系统 CA + 内置的新根证书（Android 6 缺 ISRG Root X1/X2）。 */
    static SSLContext sslContext(Context ctx) throws IOException {
        SSLContext cached = sCtx;
        if (cached != null) return cached;
        try {
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);
            try {
                KeyStore sys = KeyStore.getInstance("AndroidCAStore");
                sys.load(null, null);
                for (String a : Collections.list(sys.aliases())) {
                    Certificate c = sys.getCertificate(a);
                    if (c != null) ks.setCertificateEntry(a, c);
                }
            } catch (Throwable ignored) { }
            try {
                CertificateFactory cf = CertificateFactory.getInstance("X.509");
                InputStream in = ctx.getResources().openRawResource(R.raw.extra_roots);
                int i = 0;
                for (Certificate c : cf.generateCertificates(in)) {
                    ks.setCertificateEntry("extra" + (i++), c);
                }
                in.close();
            } catch (Throwable ignored) { }
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ks);
            SSLContext c = SSLContext.getInstance("TLS");
            c.init(null, tmf.getTrustManagers(), null);
            sCtx = c;
            return c;
        } catch (Exception e) {
            throw new IOException("TLS context failed", e);
        }
    }

    private static final class Resp {
        int status;
        String location;
        String body;
    }

    private static Resp request(String urlStr, Network net, int timeoutMs, Context ctx) throws IOException {
        URL url = new URL(urlStr);
        String host = url.getHost();
        boolean https = "https".equalsIgnoreCase(url.getProtocol());
        int port = url.getPort() > 0 ? url.getPort() : (https ? 443 : 80);
        String path = (url.getFile() == null || url.getFile().isEmpty()) ? "/" : url.getFile();

        InetAddress addr = resolveTcp(host, net, timeoutMs);
        Socket raw = new Socket();
        try {
            if (net != null && Build.VERSION.SDK_INT >= 23) {
                try { net.bindSocket(raw); } catch (Throwable ignored) { }
            }
            raw.connect(new InetSocketAddress(addr, port), timeoutMs);
            raw.setSoTimeout(timeoutMs);

            Socket s = raw;
            if (https) {
                SSLSocketFactory f = sslContext(ctx).getSocketFactory();
                SSLSocket ssl = (SSLSocket) f.createSocket(raw, host, port, true);
                ssl.setSoTimeout(timeoutMs);
                ssl.startHandshake();
                HostnameVerifier hv = HttpsURLConnection.getDefaultHostnameVerifier();
                if (!hv.verify(host, ssl.getSession()))
                    throw new IOException("hostname verify failed: " + host);
                s = ssl;
            }

            OutputStream os = s.getOutputStream();
            String req = "GET " + path + " HTTP/1.1\r\n"
                    + "Host: " + host + "\r\n"
                    + "User-Agent: " + UA + "\r\n"
                    + "Accept: */*\r\n"
                    + "Accept-Encoding: gzip\r\n"
                    + "Connection: close\r\n\r\n";
            os.write(req.getBytes("ISO-8859-1"));
            os.flush();

            InputStream in = s.getInputStream();
            String statusLine = readLine(in);
            if (statusLine == null) throw new IOException("no response");
            String[] sp = statusLine.split(" ");
            Resp r = new Resp();
            r.status = sp.length > 1 ? parseInt(sp[1]) : 0;

            Map<String, String> headers = new HashMap<>();
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int c = line.indexOf(':');
                if (c > 0) {
                    headers.put(line.substring(0, c).trim().toLowerCase(Locale.US),
                            line.substring(c + 1).trim());
                }
            }
            r.location = headers.get("location");

            byte[] body = readBody(in, headers);
            s.close();
            raw = null;

            String enc = headers.get("content-encoding");
            if (enc != null && enc.toLowerCase(Locale.US).contains("gzip")) {
                GZIPInputStream gz = new GZIPInputStream(new java.io.ByteArrayInputStream(body));
                body = readAll(gz);
            }
            r.body = new String(body, "UTF-8");
            return r;
        } finally {
            if (raw != null) try { raw.close(); } catch (IOException ignored) { }
        }
    }

    private static byte[] readBody(InputStream in, Map<String, String> headers) throws IOException {
        String te = headers.get("transfer-encoding");
        if (te != null && te.toLowerCase(Locale.US).contains("chunked")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            while (true) {
                String sz = readLine(in);
                if (sz == null) break;
                int semi = sz.indexOf(';');
                if (semi >= 0) sz = sz.substring(0, semi);
                int n;
                try { n = Integer.parseInt(sz.trim(), 16); } catch (Exception e) { break; }
                if (n <= 0) { readLine(in); break; }
                byte[] buf = new byte[n];
                readFully(in, buf, n);
                out.write(buf, 0, n);
                readLine(in);
            }
            return out.toByteArray();
        }
        String cl = headers.get("content-length");
        if (cl != null) {
            int n = parseInt(cl);
            if (n > 0) {
                byte[] buf = new byte[n];
                readFully(in, buf, n);
                return buf;
            }
        }
        return readAll(in);
    }

    /* ---------- DNS over TCP ---------- */

    static InetAddress resolveTcp(String host, Network net, int timeoutMs) throws IOException {
        /* 本身是 IPv4 字面量直接返回；域名不走系统 UDP DNS（车机上不可用） */
        if (host.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
            try { return InetAddress.getByName(host); } catch (Exception ignored) { }
        }
        for (String r : RESOLVERS) {
            try {
                InetAddress a = queryTcp(host, r, net, timeoutMs);
                if (a != null) return a;
            } catch (Exception ignored) { }
        }
        /* 最后退回系统解析（UDP），部分网络可能可用 */
        return InetAddress.getByName(host);
    }

    private static InetAddress queryTcp(String host, String resolver, Network net, int timeoutMs) throws IOException {
        Socket s = new Socket();
        try {
            if (net != null && Build.VERSION.SDK_INT >= 23) {
                try { net.bindSocket(s); } catch (Throwable ignored) { }
            }
            s.connect(new InetSocketAddress(resolver, 53), timeoutMs);
            s.setSoTimeout(timeoutMs);

            byte[] q = buildQuery(host, 0x1234);
            byte[] framed = new byte[q.length + 2];
            framed[0] = (byte) (q.length >> 8);
            framed[1] = (byte) (q.length & 0xff);
            System.arraycopy(q, 0, framed, 2, q.length);
            s.getOutputStream().write(framed);
            s.getOutputStream().flush();

            InputStream in = s.getInputStream();
            byte[] len = new byte[2];
            readFully(in, len, 2);
            int n = ((len[0] & 0xff) << 8) | (len[1] & 0xff);
            if (n <= 0 || n > 4096) return null;
            byte[] resp = new byte[n];
            readFully(in, resp, n);
            return parseFirstA(resp);
        } finally {
            try { s.close(); } catch (IOException ignored) { }
        }
    }

    private static byte[] buildQuery(String host, int id) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(id >> 8); b.write(id & 0xff);
        b.write(0x01); b.write(0x00);       // RD
        b.write(0); b.write(1);             // QDCOUNT
        b.write(0); b.write(0);             // ANCOUNT
        b.write(0); b.write(0);             // NSCOUNT
        b.write(0); b.write(0);             // ARCOUNT
        for (String part : host.split("\\.")) {
            byte[] p = part.getBytes();
            b.write(p.length);
            b.write(p, 0, p.length);
        }
        b.write(0);
        b.write(0); b.write(1);             // QTYPE A
        b.write(0); b.write(1);             // QCLASS IN
        return b.toByteArray();
    }

    private static InetAddress parseFirstA(byte[] d) {
        if (d.length < 12) return null;
        int qd = ((d[4] & 0xff) << 8) | (d[5] & 0xff);
        int an = ((d[6] & 0xff) << 8) | (d[7] & 0xff);
        int p = 12;
        for (int i = 0; i < qd; i++) {
            p = skipName(d, p);
            p += 4;
        }
        for (int i = 0; i < an; i++) {
            p = skipName(d, p);
            if (p + 10 > d.length) return null;
            int type = ((d[p] & 0xff) << 8) | (d[p + 1] & 0xff);
            int rdlen = ((d[p + 8] & 0xff) << 8) | (d[p + 9] & 0xff);
            p += 10;
            if (type == 1 && rdlen == 4 && p + 4 <= d.length) {
                try {
                    return InetAddress.getByAddress(new byte[]{d[p], d[p + 1], d[p + 2], d[p + 3]});
                } catch (Exception ignored) { }
            }
            p += rdlen;
        }
        return null;
    }

    private static int skipName(byte[] d, int p) {
        while (p < d.length) {
            int len = d[p] & 0xff;
            if (len == 0) return p + 1;
            if ((len & 0xc0) == 0xc0) return p + 2;
            p += len + 1;
        }
        return p;
    }

    /* ---------- IO helpers ---------- */

    private static String readLine(InputStream in) throws IOException {
        StringBuilder b = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.append((char) c);
            if (b.length() > 4096) break;
        }
        return b.length() == 0 && c == -1 ? null : b.toString();
    }

    private static void readFully(InputStream in, byte[] buf, int n) throws IOException {
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r <= 0) throw new IOException("eof");
            off += r;
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; }
    }
}
