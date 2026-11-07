package com.sunjunior.caresimvpn;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

public class EsimVpnService extends VpnService {
    private static final String TAG = "carvpn";
    private static final String RELAY_EXEC = CarVpnRelay.EXEC_NAME;
    private static final int RELAY_PORT = CarVpnRelay.PORT;
    /** 单节点完整探活超时：TLS 握手 + CONNECT 200 共用这一份预算 */
    private static final int PROBE_TIMEOUT_MS = 3000;
    private static final String RELAY_LOG_TAG = "carvpn-relay";
    static final String ACTION_STOP = "com.sunjunior.caresimvpn.action.STOP";

    /** 供 UI 读取的运行状态 */
    static volatile boolean running = false;
    static volatile String currentLabel = "";

    private final Object startLock = new Object();
    private volatile boolean destroyed = false;
    private volatile boolean starting = false;
    private volatile boolean stopRequested = false;
    private volatile NodeStore.Node current;

    private volatile long lastFailoverAt = 0;
    private volatile int refusedCount = 0;
    private volatile long refusedWindow = 0;
    /** 当前 relay/适配器绑定的出口网络句柄（0 = 未指定） */
    private volatile long upstreamNetHandle = 0;
    private volatile long lastUpstreamSwitchAt = 0;

    private ParcelFileDescriptor tun;
    private File config;
    private Process relay;
    private TlsUpstream tlsUp;
    private Thread watchdog;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            /* 停止放到子线程：native stop 可能阻塞，不能卡主线程 */
            stopRequested = true;
            destroyed = true;
            new Thread(new Runnable() {
                @Override public void run() {
                    synchronized (startLock) {
                        stopAllLocked();
                        starting = false;
                    }
                    stopForeground(true);
                    stopSelf();
                }
            }, "carvpn-stop").start();
            return START_NOT_STICKY;
        }
        stopRequested = false;
        if (starting || running) return START_STICKY;
        destroyed = false;
        starting = true;
        startForeground(1, buildNotification("正在启动…"));
        new Thread(new Runnable() {
            @Override public void run() { startInternal(null); }
        }, "carvpn-start").start();
        return START_STICKY;
    }

    /** 选择节点 → 起 relay → 建 tun → 起 hev tunnel。forced != null 时为故障切换。 */
    private void startInternal(NodeStore.Node forced) {
        synchronized (startLock) {
            if (destroyed) { starting = false; return; }
            stopAllLocked();

            /* 探活 socket 也要绑定到真实上网网络（WiFi/eth0），否则会被自己的 VPN 套住 */
            NodeProbe.bindNetwork = upstreamNetwork();
            NodeProbe.appContext = this;

            List<NodeStore.Node> nodes = NodeStore.loadOrMigrate(this);
            List<String> pkgs = WhitelistStore.load(this);
            if (nodes.isEmpty() || pkgs.isEmpty()) {
                Log.w(TAG, nodes.isEmpty() ? "no node configured" : "whitelist empty");
                stopSelfFail();
                return;
            }

            NodeStore.Node chosen = forced;
            if (chosen == null) {
                /* 直接使用界面当前选中的节点；"自动选最快"在勾选复选框/导入订阅时已完成 */
                int idx = NodeStore.getActive(this);
                if (idx < 0 || idx >= nodes.size()) idx = 0;
                chosen = nodes.get(idx);
            }
            if (chosen == null) chosen = nodes.get(0);

            /* 记录实际使用的节点，保证界面与运行一致（含故障切换） */
            for (int i = 0; i < nodes.size(); i++) {
                if (nodes.get(i).key().equals(chosen.key())) {
                    NodeStore.setActive(this, i);
                    break;
                }
            }

            if (!startRelay(chosen)) {
                stopSelfFail();
                return;
            }

            try {
                VpnService.Builder b = new VpnService.Builder()
                        .setSession("car-esim-vpn")
                        .addAddress("26.26.26.1", 32)
                        .addDnsServer("26.26.26.2")
                        .addRoute("0.0.0.0", 0)
                        .setMtu(1400);
                for (String p : pkgs) {
                    try {
                        b.addAllowedApplication(p);
                    } catch (PackageManager.NameNotFoundException e) {
                        Log.w(TAG, "skip pkg: " + p);
                    }
                }
                tun = b.establish();
            } catch (Exception e) {
                Log.e(TAG, "establish failed", e);
                tun = null;
            }
            if (tun == null) {
                Log.e(TAG, "tun establish failed");
                stopSelfFail();
                return;
            }

            try {
                config = writeConfig();
            } catch (Exception e) {
                Log.e(TAG, "writeConfig failed", e);
                stopSelfFail();
                return;
            }

            boolean ok = CarVpnTunnel.TProxyStartService(config.getAbsolutePath(), tun.getFd());
            Log.i(TAG, "tunnel start: " + ok + " node=" + chosen.label());
            if (!ok) {
                stopSelfFail();
                return;
            }

            current = chosen;
            currentLabel = chosen.label();
            running = true;
            starting = false;
            startForeground(1, buildNotification("节点 " + chosen.label() + " · 已代理 " + pkgs.size() + " 个应用"));
            startWatchdog(chosen);
        }
    }

    /** 并发探活所有节点，选时延最低且 TLS 握手 + CONNECT 200 都成功的。 */
    private NodeStore.Node pickFastest(List<NodeStore.Node> nodes) {
        long[] lat = NodeProbe.probeAll(nodes, PROBE_TIMEOUT_MS);
        int idx = NodeStore.fastestIndex(lat);
        if (idx < 0) {
            Log.w(TAG, "no node passed probe, fallback to first");
            return nodes.get(0);
        }
        Log.i(TAG, "auto pick #" + idx + " " + nodes.get(idx).label() + " " + lat[idx] + "ms");
        return nodes.get(idx);
    }

    /**
     * 选择出口网络：有 WiFi 走 WiFi，没有则走 eth0（详见 {@link UpstreamNet}）。
     * relay / TLS 适配器 / 探活 socket 都绑定到它，避免被自己的 VPN 套住形成回环。
     */
    private Network upstreamNetwork() {
        if (Build.VERSION.SDK_INT < 23) return null;
        Network net = UpstreamNet.pick(this);
        if (net == null) Log.w(TAG, "no upstream network (wifi/eth0) available");
        return net;
    }

    private String upstreamHandle() {
        try {
            Network net = upstreamNetwork();
            if (net != null) {
                long h = net.getNetworkHandle();
                upstreamNetHandle = h;
                Log.i(TAG, "upstream network: " + UpstreamNet.typeOf(this, net) + " handle=" + h);
                return String.valueOf(h);
            }
        } catch (Throwable t) {
            Log.w(TAG, "upstream handle failed: " + t);
        }
        return "0";
    }

    /**
     * 拉起本地 relay（socks5 前端 + dnsu2t），hev-tunnel 的上游指向 127.0.0.1:8101。
     * 参数：port / up_host / up_port / up_user / up_pass / resolver / upstream_net_handle。
     */
    private boolean startRelay(NodeStore.Node n) {
        try {
            File exe = new File(getApplicationInfo().nativeLibraryDir, CarVpnRelay.EXEC_NAME);
            if (!exe.isFile()) {
                Log.e(TAG, "relay exe missing: " + exe.getAbsolutePath());
                return false;
            }
            List<String> cmd = new ArrayList<>();
            cmd.add(exe.getAbsolutePath());
            cmd.add(String.valueOf(RELAY_PORT));
            /* 节点是 HTTPS 代理（tls:true）时，先起本地 TLS 适配器，relay 上游指向它 */
            String upHost = n.host;
            int upPort = n.port;
            if (n.tls) {
                try {
                    tlsUp = new TlsUpstream(this, upstreamNetwork(), n.host, n.port);
                    upPort = tlsUp.start();
                    upHost = "127.0.0.1";
                    Log.i(TAG, "tls upstream ready: 127.0.0.1:" + upPort + " -> " + n.host + ":" + n.port);
                } catch (Throwable t) {
                    Log.e(TAG, "tls upstream failed, fallback to plain", t);
                    if (tlsUp != null) { tlsUp.stop(); tlsUp = null; }
                }
            }
            cmd.add(upHost);
            cmd.add(String.valueOf(upPort));
            cmd.add(n.username == null ? "" : n.username);
            cmd.add(n.password == null ? "" : n.password);
            cmd.add("114.114.114.114,223.5.5.5,8.8.8.8");
            cmd.add(upstreamHandle());
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            relay = pb.start();

            final BufferedReader br = new BufferedReader(new InputStreamReader(relay.getInputStream()));
            Thread lt = new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        String line;
                        while ((line = br.readLine()) != null) {
                            Log.i(RELAY_LOG_TAG, line);
                            if (line.indexOf("upstream refused") >= 0
                                    && line.indexOf(":53)") < 0
                                    && line.indexOf("(192.168.") < 0
                                    && line.indexOf("(10.") < 0
                                    && line.indexOf("(172.") < 0
                                    && line.indexOf("(127.") < 0
                                    && line.indexOf("(26.26.") < 0) {
                                countRefused();   /* 本地/内网目标的拒绝不计入故障切换 */
                            }
                        }
                    } catch (Exception ignored) { }
                }
            }, "carvpn-relay-log");
            lt.start();
            try { Thread.sleep(150); } catch (InterruptedException ignored) { }
            Log.i(TAG, "relay started: " + exe.getAbsolutePath() + " -> " + n.label());
            return true;
        } catch (Exception e) {
            Log.e(TAG, "relay start failed", e);
            return false;
        }
    }

    /** 上游连续拒绝 → 触发切换（60 秒窗口内累计 5 次）。 */
    private void countRefused() {
        long now = System.currentTimeMillis();
        if (now - refusedWindow > 60000) {
            refusedWindow = now;
            refusedCount = 0;
        }
        if (++refusedCount >= 15) {
            refusedCount = 0;
            final NodeStore.Node cur = current;
            if (cur != null) {
                new Thread(new Runnable() {
                    @Override public void run() {
                        /* 先确认节点本身不可用，避免上游对个别目标的正常拒绝导致频繁切换 */
                        if (NodeProbe.probe(cur, 2500) <= 0) failover(cur);
                    }
                }, "carvpn-failover").start();
            }
        }
    }

    /** 运行期健康检查：连续 2 次连不上就切下一个可用节点；切不动则继续监控。 */
    private void startWatchdog(final NodeStore.Node cur) {
        watchdog = new Thread(new Runnable() {
            @Override public void run() {
                int fails = 0;
                while (!destroyed && running) {
                    try { Thread.sleep(20000); } catch (InterruptedException e) { return; }
                    if (destroyed || !running) return;

                    /* 出口变化（WiFi 接入/断开）时重新绑定，避免继续走失效的旧网络 */
                    long now = System.currentTimeMillis();
                    if (now - lastUpstreamSwitchAt > 30000) {
                        Network want = UpstreamNet.pick(EsimVpnService.this);
                        long wantH = want == null ? 0 : want.getNetworkHandle();
                        if (wantH != 0 && wantH != upstreamNetHandle) {
                            lastUpstreamSwitchAt = now;
                            Log.i(TAG, "upstream changed " + upstreamNetHandle + " -> " + wantH
                                    + " (" + UpstreamNet.typeOf(EsimVpnService.this, want) + "), rebinding");
                            startInternal(cur);
                            return;
                        }
                    }

                    long ms = NodeProbe.tcpLatency(cur.host, cur.port, 3000);
                    if (ms > 0) { fails = 0; continue; }
                    if (++fails < 2) continue;
                    Log.w(TAG, "node unhealthy, switching");
                    if (failover(cur)) return;   // 成功切换后由新实例接管
                    fails = 0;                   // 没切成：继续监控原节点
                }
            }
        }, "carvpn-watchdog");
        watchdog.start();
    }

    /**
     * 故障切换：先做完整 CONNECT 探活，失败再退化为 TCP 探活（避免上游短暂拒绝导致误判），
     * 都没有可用节点则返回 false，由看门狗继续观察。
     */
    private boolean failover(NodeStore.Node from) {
        if (destroyed) return false;
        long now = System.currentTimeMillis();
        if (now - lastFailoverAt < 15000) return false;

        List<NodeStore.Node> nodes = NodeStore.load(this);
        if (nodes.size() < 2) return false;

        NodeStore.Node next = pickFallback(nodes, from);
        if (next == null) {
            Log.w(TAG, "no healthy fallback node (will keep watching)");
            return false;
        }
        lastFailoverAt = now;
        Log.i(TAG, "failover " + from.label() + " -> " + next.label());
        startInternal(next);
        return true;
    }

    private NodeStore.Node pickFallback(List<NodeStore.Node> nodes, NodeStore.Node from) {
        NodeStore.Node best = null;
        long bestMs = Long.MAX_VALUE;

        /* 第一轮：完整 CONNECT 探活，取最快 */
        for (NodeStore.Node c : nodes) {
            if (c.key().equals(from.key())) continue;
            long ms = NodeProbe.probe(c, 2000);
            if (ms > 0 && ms < bestMs) { bestMs = ms; best = c; }
        }
        if (best != null) return best;

        /* 第二轮：上游可能只是暂时拒绝新会话，用 TCP 探活兜底 */
        for (NodeStore.Node c : nodes) {
            if (c.key().equals(from.key())) continue;
            if (NodeProbe.tcpLatency(c.host, c.port, 1500) > 0) {
                Log.w(TAG, "fallback by tcp-only: " + c.label());
                return c;
            }
        }
        return null;
    }

    /** 生成 hev-tunnel 配置：socks5 address=127.0.0.1 (local relay) / udp='udp' / mtu */
    private File writeConfig() throws IOException {
        File dir = getCacheDir();
        /* 固定文件名，每次启动直接覆盖；顺带清掉历史时间戳文件，避免缓存膨胀 */
        File[] old = dir.listFiles();
        if (old != null) {
            for (File o : old) {
                String n = o.getName();
                if (n.startsWith("hev_") && n.endsWith(".yml")) o.delete();
            }
        }
        File f = new File(dir, "hev_current.yml");
        String y =
                "tunnel:\n" +
                "  mtu: 1400\n" +
                "socks5:\n" +
                "  address: 127.0.0.1\n" +
                "  port: 8101\n" +
                "  udp: 'udp'\n" +
                "misc:\n" +
                "  log-file: stderr\n" +
                "  log-level: info\n";
        FileWriter w = new FileWriter(f);
        try { w.write(y); } finally { w.close(); }
        return f;
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        return new Notification.Builder(this)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("CarVPN 运行中")
                .setContentText(text)
                .setContentIntent(PendingIntent.getActivity(this, 0, open, 0))
                .build();
    }

    private int stopSelfFail() {
        synchronized (startLock) {
            stopAllLocked();
            starting = false;
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void stopAllLocked() {
        running = false;
        currentLabel = "";
        current = null;
        if (relay != null) {
            try { relay.destroy(); } catch (Exception ignored) { }
            relay = null;
        }
        if (tlsUp != null) {
            try { tlsUp.stop(); } catch (Exception ignored) { }
            tlsUp = null;
        }
        if (config != null) {
            try { config.delete(); } catch (Exception ignored) { }
            config = null;
        }
        if (CarVpnTunnel.TProxyIsRunning()) {
            CarVpnTunnel.TProxyStopService();
        }
        if (tun != null) {
            try { tun.close(); } catch (IOException ignored) { }
            tun = null;
        }
        if (watchdog != null) {
            watchdog.interrupt();
            watchdog = null;
        }
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        running = false;
        currentLabel = "";
        if (!stopRequested) {
            /* 兜底清理：只释放资源，不调用可能阻塞的 native stop */
            try { if (relay != null) relay.destroy(); } catch (Exception ignored) { }
            relay = null;
            try { if (tlsUp != null) tlsUp.stop(); } catch (Exception ignored) { }
            tlsUp = null;
            try { if (tun != null) tun.close(); } catch (IOException ignored) { }
            tun = null;
            if (config != null) { try { config.delete(); } catch (Exception ignored) { } config = null; }
            if (watchdog != null) { watchdog.interrupt(); watchdog = null; }
        }
        stopForeground(true);
        super.onDestroy();
    }
}
