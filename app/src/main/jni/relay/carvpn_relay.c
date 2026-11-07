/*
 * carvpn_relay.c — 本地 relay：hev-socks5-tunnel 的上游垫片。
 *
 * 链路（docs/probe-results.md 实测定案）：
 *   hev(tun) --socks5--> 127.0.0.1:8101(本进程) --http CONNECT--> vendor 节点 --> 目标
 *
 *   TCP    : 本地 socks5 服务端 CONNECT → 上游 http CONNECT 透传（Basic 认证可选）
 *   UDP/53 : dnsu2t —— 上游无 UDP 数据面，改为经上游 CONNECT <resolver>:53 走
 *            DNS-over-TCP（2 字节长度前缀），再以 socks5-UDP framing 应答
 *   其余 UDP 目的端口：丢弃（M1 只做 DNS）
 *
 * 位于 app/src/main/jni/relay/，由 jni/Android.mk 以 BUILD_EXECUTABLE 编译，
 * 模块名 libcarvpnrelay.so 以便打进 APK 并解包到 nativeLibraryDir 直接 exec。
 *
 * 用法: libcarvpnrelay.so <listen_port> <up_host> <up_port> [up_user] [up_pass] [resolver_ip]
 *   例: libcarvpnrelay.so 8101 node.example.net 443 uuid secret 114.114.114.114
 */
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <netdb.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <poll.h>
#include <pthread.h>
#include <signal.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/resource.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <time.h>
#include <unistd.h>

#define HANDSHAKE_TIMEOUT_S 8   /* 与上游建连/握手/应答头超时 */
#define IO_TIMEOUT_S 15         /* 握手期单次读超时（此后 pump 用 poll 阻塞） */
#define IO_BUF 32768
#define UDP_HDR_MAX 300         /* socks5-UDP 头上限（ATYP=3 域名 253 字节） */
#define DNS_QUERY_MAX 4096

/* ---- 资源上限（防止 fd/线程被长连接耗尽） ---- */
#define IDLE_TIMEOUT_S 240      /* 数据面空闲超过该值强制回收 */
#define MAX_CLIENTS 192         /* 并发 TCP 客户端线程上限 */
#define MAX_DNS 32              /* 并发 DNS 转发线程上限 */
#define DNS_CACHE_N 128         /* DNS 应答缓存槽位数 */
#define DNS_TTL_S 60            /* DNS 缓存 TTL */
#define UDP_CTRL_TIMEOUT_S 300  /* UDP ASSOCIATE 控制连接空闲回收 */
#define FD_SOFT_LIMIT 8192

static const char *g_up_host;
static int g_up_port;
static const char *g_up_user = "";
static const char *g_up_pass = "";
static char g_resolver[64] = "114.114.114.114";
#define MAX_RESOLVERS 3
static char g_resolvers[MAX_RESOLVERS][64];
static int g_nres = 0;
static int g_dns_seen = 0;

/* 绑定到指定网络（eth0），避免 relay 的上游连接被自己的 VPN 套住形成回环。
 * Android 6 (API 23) 起 libandroid.so 提供这两个接口。 */
typedef uint64_t net_handle_t;
extern int android_setprocnetwork(net_handle_t network) __attribute__((weak));
extern int android_setsocknetwork(net_handle_t network, int fd) __attribute__((weak));
static net_handle_t g_net = 0;
static int g_port = 8101;
static int g_udp = -1;
static pthread_attr_t g_attr;

/* 并发计数 */
static pthread_mutex_t g_cnt_lock = PTHREAD_MUTEX_INITIALIZER;
static int g_clients = 0;
static int g_dns = 0;
static long g_drop_clients = 0;
static long g_drop_dns = 0;

/* DNS 应答缓存（按查询体做 key，忽略 2 字节 ID） */
struct dns_cache {
    unsigned int key;
    int len;
    time_t exp;
    unsigned char resp[DNS_QUERY_MAX];
};
static struct dns_cache g_dc[DNS_CACHE_N];
static pthread_mutex_t g_dc_lock = PTHREAD_MUTEX_INITIALIZER;
static unsigned int g_dc_next = 0;


static void logi(const char *fmt, ...)
{
    va_list ap;
    fputs("[relay] ", stderr);
    va_start(ap, fmt);
    vfprintf(stderr, fmt, ap);
    va_end(ap);
    fputc('\n', stderr);
    fflush(stderr);
}

/* ---------- 小工具 ---------- */

static int wr(int fd, const void *buf, size_t n)
{
    const unsigned char *p = buf;
    while (n) {
        ssize_t w = write(fd, p, n);
        if (w <= 0) {
            if (w < 0 && errno == EINTR)
                continue;
            return 0;
        }
        p += w;
        n -= (size_t)w;
    }
    return 1;
}

static int readn(int fd, void *buf, size_t n)
{
    unsigned char *p = buf;
    size_t got = 0;
    while (got < n) {
        ssize_t r = read(fd, p + got, n - got);
        if (r <= 0) {
            if (r < 0 && errno == EINTR)
                continue;
            return 0;
        }
        got += (size_t)r;
    }
    return 1;
}

static void b64enc(const char *in, char *out, size_t outsz)
{
    static const char t[] =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    size_t n = strlen(in), i, o = 0;
    for (i = 0; i < n && o + 4 < outsz; i += 3) {
        unsigned v = ((unsigned char)in[i]) << 16;
        if (i + 1 < n)
            v |= ((unsigned char)in[i + 1]) << 8;
        if (i + 2 < n)
            v |= (unsigned char)in[i + 2];
        out[o++] = t[(v >> 18) & 63];
        out[o++] = t[(v >> 12) & 63];
        out[o++] = (i + 1 < n) ? t[(v >> 6) & 63] : '=';
        out[o++] = (i + 2 < n) ? t[v & 63] : '=';
    }
    out[o] = 0;
}

/* 读到 HTTP 头结束（\r\n\r\n）。*end = 头结束偏移，*gotp = 已读总长；
 * head 中 end..got 的残余字节属于隧道首包，调用方不得丢弃。 */
static int http_read_headers(int fd, char *head, int bufsz, int *end, int *gotp)
{
    int got = 0;
    *end = -1;
    *gotp = 0;
    for (;;) {
        char *m;
        ssize_t r = read(fd, head + got, (size_t)(bufsz - 1 - got));
        if (r <= 0) {
            if (r < 0 && errno == EINTR)
                continue;
            return 0;
        }
        got += (int)r;
        head[got] = 0;
        m = strstr(head, "\r\n\r\n");
        if (m) {
            *end = (int)(m - head) + 4;
            *gotp = got;
            return 1;
        }
        if (got >= bufsz - 1)
            return 0;
    }
}

/* ---------- 上游（vendor http CONNECT 节点） ---------- */

/* 建连 → CONNECT 应答 200。成功返回就绪 fd；
 * pre/pren 抄出头后已到达的首包残余字节（正常为 0）。 */
static int up_connect(const char *dst, unsigned dport, char *pre, size_t *pren)
{
    char svc[8], req[768], head[1024];
    struct addrinfo hints, *res;
    int fd = -1, fl, n, end, got, one = 1;
    *pren = 0;

    snprintf(svc, sizeof svc, "%u", g_up_port);
    memset(&hints, 0, sizeof hints);
    hints.ai_socktype = SOCK_STREAM;
    {
        struct in_addr tmp;
        if (inet_pton(AF_INET, g_up_host, &tmp) == 1)
            hints.ai_flags = AI_NUMERICHOST;   /* IP 直连，不走 netd 解析 */
    }
    if (getaddrinfo(g_up_host, svc, &hints, &res) != 0 || !res) {
        logi("up: getaddrinfo(%s) fail", g_up_host);
        return -1;
    }
    fd = socket(res->ai_family, SOCK_STREAM, 0);
    if (fd < 0) {
        logi("up: socket fail: %s", strerror(errno));
        freeaddrinfo(res);
        return -1;
    }
    if (g_net && android_setsocknetwork)
        android_setsocknetwork(g_net, fd);   /* 兜底：逐 socket 绑到 eth0 */
    setsockopt(fd, SOL_SOCKET, SO_KEEPALIVE, &one, sizeof one);
    setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof one);

    /* 非阻塞 connect + poll 超时（alarm 在多线程下不安全，不用） */
    fl = fcntl(fd, F_GETFL, 0);
    fcntl(fd, F_SETFL, fl | O_NONBLOCK);
    n = connect(fd, res->ai_addr, res->ai_addrlen);
    freeaddrinfo(res);
    if (n != 0 && errno != EINPROGRESS) {
        logi("up: connect fail: %s", strerror(errno));
        close(fd);
        return -1;
    }
    if (n != 0) {
        struct pollfd pf;
        int soerr = 0;
        socklen_t sl = sizeof soerr;
        pf.fd = fd;
        pf.events = POLLOUT;
        pf.revents = 0;
        if (poll(&pf, 1, HANDSHAKE_TIMEOUT_S * 1000) != 1 ||
            getsockopt(fd, SOL_SOCKET, SO_ERROR, &soerr, &sl) != 0 || soerr != 0) {
            logi("up: poll/soerr fail (soerr=%d)", soerr);
            close(fd);
            return -1;
        }
    }
    fcntl(fd, F_SETFL, fl);

    {
        struct timeval tv;
        tv.tv_sec = IO_TIMEOUT_S;
        tv.tv_usec = 0;
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof tv);
        setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof tv);
    }

    {
        char auth[600] = "", raw[320], enc[560];
        if (g_up_user[0]) {
            snprintf(raw, sizeof raw, "%s:%s", g_up_user, g_up_pass);
            b64enc(raw, enc, sizeof enc);
            snprintf(auth, sizeof auth, "Proxy-Authorization: Basic %s\r\n", enc);
        }
        n = snprintf(req, sizeof req,
                     "CONNECT %s:%u HTTP/1.1\r\n"
                     "Host: %s:%u\r\n"
                     "%sProxy-Connection: keep-alive\r\n\r\n",
                     dst, dport, dst, dport, auth);
        if (n <= 0 || (size_t)n >= sizeof req || !wr(fd, req, (size_t)n)) {
            logi("up: write CONNECT fail");
            close(fd);
            return -1;
        }
    }

    if (!http_read_headers(fd, head, (int)sizeof head, &end, &got)) {
        logi("up: read CONNECT reply fail");
        close(fd);
        return -1;
    }
    {
        /* 只看首行状态码 */
        char *nl = memchr(head, '\n', (size_t)end);
        int ok = nl != NULL &&
                 (0 == strncmp(head, "HTTP/1.0 200", 12) ||
                  0 == strncmp(head, "HTTP/1.1 200", 12));
        if (!ok) {
            head[strcspn(head, "\r\n")] = 0;
            logi("upstream refused: %s (%s:%u)", head, dst, dport);
            close(fd);
            return -1;
        }
    }
    if (end < got) {
        size_t left = (size_t)(got - end);
        if (left > IO_BUF)
            left = IO_BUF;
        memcpy(pre, head + end, left);
        *pren = left;
        logi("up: unexpected residue %d bytes", (int)left);
    }

    {
        /* 撤销读写超时：数据面由 pump 的 poll 管 */
        struct timeval tv0;
        tv0.tv_sec = 0;
        tv0.tv_usec = 0;
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv0, sizeof tv0);
        setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &tv0, sizeof tv0);
    }
    return fd;
}

/* ---------- 双向转发（poll 循环；半关闭语义透传） ---------- */

static void pump(int cfd, int ufd, const char *pre, size_t prelen)
{
    struct pollfd p[2];
    char *bc = malloc(IO_BUF);  /* 客户端→上游 待发 */
    char *bu = malloc(IO_BUF);  /* 上游→客户端 待发 */
    size_t lc = 0, lu = 0;
    int ceof = 0, ueof = 0;
    int idle = 0;

    if (!bc || !bu) {
        free(bc);
        free(bu);
        return;
    }
    if (prelen > IO_BUF)
        prelen = IO_BUF;
    if (prelen) {
        memcpy(bu, pre, prelen);
        lu = prelen;
    }

    for (;;) {
        p[0].fd = cfd;
        p[0].revents = 0;
        p[0].events = 0;
        p[1].fd = ufd;
        p[1].revents = 0;
        p[1].events = 0;
        if (!ceof && lc < IO_BUF)
            p[0].events |= POLLIN;
        if (lu > 0)
            p[0].events |= POLLOUT;
        if (!ueof && lu < IO_BUF)
            p[1].events |= POLLIN;
        if (lc > 0)
            p[1].events |= POLLOUT;
        if (p[0].events == 0 && p[1].events == 0)
            break;
        {
            int pr = poll(p, 2, 1000);
            if (pr < 0) {
                if (errno == EINTR)
                    continue;
                break;
            }
            if (pr == 0) {
                /* 空闲回收：避免半关闭/僵尸连接长期占用线程与 fd */
                if (++idle >= IDLE_TIMEOUT_S)
                    break;
                continue;
            }
            idle = 0;
        }
        if (p[0].revents & (POLLERR | POLLNVAL))
            break;
        if (p[1].revents & (POLLERR | POLLNVAL))
            break;

        if (p[0].revents & POLLIN) {
            ssize_t n = read(cfd, bc + lc, IO_BUF - lc);
            if (n > 0)
                lc += (size_t)n;
            else if (n == 0) {
                ceof = 1;
                shutdown(ufd, SHUT_WR);   /* 半关闭透传给上游 */
            } else if (errno != EINTR)
                break;
        }
        if (p[0].revents & POLLOUT) {
            ssize_t n = write(cfd, bu, lu);
            if (n > 0) {
                memmove(bu, bu + n, lu - (size_t)n);
                lu -= (size_t)n;
            } else if (n < 0 && errno != EINTR)
                break;
        }
        if (p[1].revents & POLLIN) {
            ssize_t n = read(ufd, bu + lu, IO_BUF - lu);
            if (n > 0)
                lu += (size_t)n;
            else if (n == 0) {
                ueof = 1;
                shutdown(cfd, SHUT_WR);
            } else if (errno != EINTR)
                break;
        }
        if (p[1].revents & POLLOUT) {
            ssize_t n = write(ufd, bc, lc);
            if (n > 0) {
                memmove(bc, bc + n, lc - (size_t)n);
                lc -= (size_t)n;
            } else if (n < 0 && errno != EINTR)
                break;
        }
    }
    free(bc);
    free(bu);
}

/* ---------- socks5 请求解析 ---------- */

/* 读取 ATYP+地址+端口，dst 拼字符串，port 返回 */
static int read_addr(int c, unsigned char atyp, char *dst, int dstsz, unsigned *portp)
{
    unsigned char b[320];
    unsigned port;
    if (atyp == 1) {
        if (!readn(c, b, 4) || !inet_ntop(AF_INET, b, dst, (socklen_t)dstsz))
            return 0;
    } else if (atyp == 3) {
        unsigned len;
        if (!readn(c, b, 1))
            return 0;
        len = b[0];
        if (len < 1 || len > 253 || !readn(c, b, len))
            return 0;
        if (len >= (unsigned)dstsz)
            return 0;
        memcpy(dst, b, len);
        dst[len] = 0;
    } else {
        return 0;   /* ATYP=4（IPv6）：tun 无 v6，拒绝 */
    }
    if (!readn(c, b, 2))
        return 0;
    port = ((unsigned)b[0] << 8) | b[1];
    *portp = port;
    return port >= 1 && port <= 65535;
}

/* UDP ASSOCIATE 请求附带的 DST 字段读掉 */
static int skip_addr(int c, unsigned char atyp)
{
    unsigned char b[320];
    if (atyp == 1)
        return readn(c, b, 6);
    if (atyp == 3) {
        if (!readn(c, b, 1))
            return 0;
        return readn(c, b, (size_t)b[0] + 2);
    }
    if (atyp == 4)
        return readn(c, b, 18);
    return 0;
}

/* ---------- TCP 客户端线程 ---------- */

static void *tcp_client(void *arg)
{
    int c = (int)(long)arg;
    unsigned char b[300];
    char dst[300], pre[1024];
    size_t prelen;
    unsigned port = 0;
    int nm, ufd = -1;
    struct timeval tv;
    static const unsigned char m_noauth[2] = { 5, 0 };
    static const unsigned char m_auth[2] = { 5, 2 };
    static const unsigned char m_none[2] = { 5, 0xff };
    const unsigned char good[10] = { 5, 0, 0, 1, 0, 0, 0, 0, 0, 0 };
    const unsigned char rep1[10] = { 5, 1, 0, 1, 0, 0, 0, 0, 0, 0 };   /* general failure */
    const unsigned char rep7[10] = { 5, 7, 0, 1, 0, 0, 0, 0, 0, 0 };   /* cmd 不支持 */

    tv.tv_sec = IO_TIMEOUT_S;
    tv.tv_usec = 0;
    setsockopt(c, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof tv);
    setsockopt(c, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof tv);

    /* 方法协商：无鉴权优先；仅提供 0x02 时走用户名/密码子协商但一律放行 */
    if (!readn(c, b, 2) || b[0] != 5 || b[1] < 1 || b[1] > 32)
        goto out;
    nm = b[1];
    if (!readn(c, b, (size_t)nm))
        goto out;
    if (memchr(b, 0x00, (size_t)nm)) {
        if (!wr(c, m_noauth, 2))
            goto out;
    } else if (memchr(b, 0x02, (size_t)nm)) {
        unsigned ulen, plen;
        static const unsigned char authok[2] = { 1, 0 };
        if (!wr(c, m_auth, 2) || !readn(c, b, 2))
            goto out;
        ulen = b[1];
        if (ulen > 0 && !readn(c, b, ulen))
            goto out;
        if (!readn(c, b, 1))
            goto out;
        plen = b[0];
        if (plen > 0 && !readn(c, b, plen))
            goto out;
        if (!wr(c, authok, 2))
            goto out;
    } else {
        wr(c, m_none, 2);
        goto out;
    }

    {
        int one = 1;
        setsockopt(c, IPPROTO_TCP, TCP_NODELAY, &one, sizeof one);
    }

    /* 请求：VER CMD RSV ATYP */
    if (!readn(c, b, 4) || b[0] != 5)
        goto out;

    if (b[1] == 3) {
        /* UDP ASSOCIATE：应答固定 BND=127.0.0.1:<listen>，控制连接存续即通道有效 */
        unsigned char ok_udp[10];
        if (!skip_addr(c, b[3]))
            goto out;
        ok_udp[0] = 5;
        ok_udp[1] = 0;
        ok_udp[2] = 0;
        ok_udp[3] = 1;
        ok_udp[4] = 127;
        ok_udp[5] = 0;
        ok_udp[6] = 0;
        ok_udp[7] = 1;
        ok_udp[8] = (unsigned char)(g_port >> 8);
        ok_udp[9] = (unsigned char)(g_port & 0xff);
        if (!wr(c, ok_udp, 10))
            goto out;
        for (;;) {
            struct pollfd cp;
            int pr;
            ssize_t n;
            cp.fd = c;
            cp.events = POLLIN;
            cp.revents = 0;
            pr = poll(&cp, 1, UDP_CTRL_TIMEOUT_S * 1000);
            if (pr <= 0)
                break;              /* 空闲 / 错误：回收控制连接 */
            n = read(c, b, sizeof b);
            if (n <= 0)
                break;
        }
        goto out;
    }

    if (b[1] != 1) {
        wr(c, rep7, 10);
        goto out;
    }

    /* CONNECT */
    if (!read_addr(c, b[3], dst, (int)sizeof dst, &port)) {
        wr(c, rep1, 10);
        goto out;
    }
    ufd = up_connect(dst, port, pre, &prelen);
    if (ufd < 0) {
        wr(c, rep1, 10);
        goto out;
    }
    if (!wr(c, good, 10))
        goto out;
    /* pre 为上游应答头后已到达的首包残余（正常为空） */
    pump(c, ufd, pre, prelen);

out:
    close(c);
    if (ufd >= 0)
        close(ufd);
    pthread_mutex_lock(&g_cnt_lock);
    g_clients--;
    pthread_mutex_unlock(&g_cnt_lock);
    return NULL;
}

/* ---------- UDP：dnsu2t（上游无 UDP 数据面 → CONNECT resolver:53 DNS-over-TCP） ---------- */

struct uq {
    struct sockaddr_storage src;
    socklen_t slen;
    unsigned char hdr[UDP_HDR_MAX];   /* 请求头原样回作应答头 */
    int hlen;
    unsigned char dns[DNS_QUERY_MAX];
    int dnslen;
};

/* ---------- DNS 应答缓存 ---------- */

static unsigned int dns_key(const unsigned char *q, int n)
{
    unsigned int h = 2166136261u;
    int i;
    for (i = 2; i < n; i++) {          /* 跳过 2 字节 ID */
        h ^= q[i];
        h *= 16777619u;
    }
    return h;
}

/* 命中返回 1，并把应答 ID 改写为本次请求的 ID */
static int dns_cache_get(unsigned int key, unsigned short id,
                         unsigned char *out, int *outlen)
{
    int i, hit = 0;
    time_t now = time(NULL);
    pthread_mutex_lock(&g_dc_lock);
    for (i = 0; i < DNS_CACHE_N; i++) {
        if (g_dc[i].key == key && g_dc[i].exp > now) {
            memcpy(out, g_dc[i].resp, (size_t)g_dc[i].len);
            out[0] = (unsigned char)(id >> 8);
            out[1] = (unsigned char)(id & 0xff);
            *outlen = g_dc[i].len;
            hit = 1;
            break;
        }
    }
    pthread_mutex_unlock(&g_dc_lock);
    return hit;
}

static void dns_cache_put(unsigned int key, const unsigned char *resp, int len)
{
    unsigned int slot;
    if (len < 12)
        return;
    pthread_mutex_lock(&g_dc_lock);
    slot = g_dc_next++ % DNS_CACHE_N;
    memcpy(g_dc[slot].resp, resp, (size_t)len);
    g_dc[slot].resp[0] = 0;            /* ID 归一，命中时再填 */
    g_dc[slot].resp[1] = 0;
    g_dc[slot].key = key;
    g_dc[slot].len = len;
    g_dc[slot].exp = time(NULL) + DNS_TTL_S;
    pthread_mutex_unlock(&g_dc_lock);
}

/* 判断应答是否被污染：A 记录落在 198.18.0.0/15 或 0.0.0.0 一律视为假应答。
 * 国内 DNS（114/223）对受限域名常返回 198.18.0.11 之类的保留段地址，
 * 直接用会导致后续 CONNECT 必然失败，这里改为换下一个解析器重试。 */
static int dns_answer_poisoned(const unsigned char *b, int n)
{
    int i = 12, qd, an, k;

    if (n < 12)
        return 1;
    qd = ((int)b[4] << 8) | b[5];
    an = ((int)b[6] << 8) | b[7];
    if (an <= 0)
        return 0;                    /* NXDOMAIN 等无 A 记录：不算污染 */

    for (k = 0; k < qd && i < n; k++) {
        while (i < n) {
            if ((b[i] & 0xc0) == 0xc0) { i += 2; break; }
            if (b[i] == 0) { i += 1; break; }
            i += 1 + b[i];
        }
        i += 4;                      /* QTYPE + QCLASS */
    }

    for (k = 0; k < an && i + 10 <= n; k++) {
        int type, dl;
        if ((b[i] & 0xc0) == 0xc0) {
            i += 2;
        } else {
            while (i < n && b[i])
                i += 1 + b[i];
            i += 1;
        }
        if (i + 10 > n)
            break;
        type = ((int)b[i] << 8) | b[i + 1];
        dl = ((int)b[i + 8] << 8) | b[i + 9];
        i += 10;
        if (i + dl > n)
            break;
        if (type == 1 && dl == 4) {
            if (b[i] == 198 && (b[i + 1] == 18 || b[i + 1] == 19))
                return 1;
            if (b[i] == 0 && b[i + 1] == 0 && b[i + 2] == 0 && b[i + 3] == 0)
                return 1;
        }
        i += dl;
    }
    return 0;
}

static void *dns_fwd(void *raw)
{
    struct uq *q = raw;
    char pre[1024];
    size_t prelen = 0;
    unsigned char lp[2], sz[2], resp[DNS_QUERY_MAX], out[UDP_HDR_MAX + DNS_QUERY_MAX];
    unsigned short id = (unsigned short)(((unsigned)q->dns[0] << 8) | q->dns[1]);
    unsigned int key = dns_key(q->dns, q->dnslen);
    int fd, clen = 0;
    int done = 0;
    int used = -1;

    if (q->dnslen > 2 && dns_cache_get(key, id, resp, &clen)) {
        memcpy(out, q->hdr, (size_t)q->hlen);
        memcpy(out + q->hlen, resp, (size_t)clen);
        sendto(g_udp, out, (size_t)(q->hlen + clen), 0,
               (const struct sockaddr *)&q->src, q->slen);
        done = 1;
    }

    if (!done) {
        int round, ri, sent = 0;
        /* 多解析器 + 重试：每个解析器最多试 2 轮 */
        for (round = 0; round < 2 && !sent; round++) {
            for (ri = 0; ri < g_nres && !sent; ri++) {
                int l2;
                prelen = 0;
                fd = up_connect(g_resolvers[ri], 53, pre, &prelen);
                if (fd < 0 || prelen) {   /* 首包残余不应存在；防御性丢弃 */
                    if (fd >= 0)
                        close(fd);
                    if (round == 1)
                        logi("dns connect fail: %s", g_resolvers[ri]);
                    continue;
                }
                lp[0] = (unsigned char)(q->dnslen >> 8);
                lp[1] = (unsigned char)(q->dnslen & 0xff);
                if (wr(fd, lp, 2) && wr(fd, q->dns, (size_t)q->dnslen) && readn(fd, sz, 2)) {
                    l2 = ((int)sz[0] << 8) | sz[1];
                    if (l2 > 0 && l2 <= (int)sizeof resp && readn(fd, resp, (size_t)l2)) {
                        if (dns_answer_poisoned(resp, l2)) {
                            logi("dns poisoned, retry next: %s", g_resolvers[ri]);
                            close(fd);
                            continue;   /* 换下一个解析器（通常是经节点的 8.8.8.8） */
                        }
                        memcpy(out, q->hdr, (size_t)q->hlen);
                        memcpy(out + q->hlen, resp, (size_t)l2);
                        sendto(g_udp, out, (size_t)(q->hlen + l2), 0,
                               (const struct sockaddr *)&q->src, q->slen);
                        dns_cache_put(key, resp, l2);
                        used = ri;
                        sent = 1;
                    }
                }
                if (!sent && round == 1)
                    logi("dns exchange fail: %s", g_resolvers[ri]);
                close(fd);
            }
        }
        if (!sent)
            logi("dns give up: len=%d", q->dnslen);
        else if (g_dns_seen <= 5 || (g_dns_seen % 100) == 0)
            logi("dns ok #%d (%d bytes) via %s", g_dns_seen, q->dnslen,
                 (used >= 0 && used < g_nres) ? g_resolvers[used] : "cache");
    }

    pthread_mutex_lock(&g_cnt_lock);
    g_dns--;
    pthread_mutex_unlock(&g_cnt_lock);
    free(q);
    return NULL;
}

static void *udp_loop(void *arg)
{
    (void)arg;
    for (;;) {
        unsigned char pkt[4096];
        struct sockaddr_storage ss;
        socklen_t sl = sizeof ss;
        ssize_t n = recvfrom(g_udp, pkt, sizeof pkt, 0, (struct sockaddr *)&ss, &sl);
        int dlen, dport, hlen = 0;

        if (n < 10)
            continue;
        /* socks5-UDP 头: RSV(2) FRAG(1) ATYP(1) ADDR PORT */
        if (pkt[2] != 0)
            continue;   /* FRAG：不支持分片 */
        switch (pkt[3]) {
        case 1:
            hlen = 4 + 4 + 2;
            break;
        case 3:
            hlen = 4 + 1 + pkt[4] + 2;
            break;
        case 4:
            hlen = 4 + 16 + 2;
            break;
        default:
            continue;
        }
        if (hlen < 4 || hlen > (int)n)
            continue;
        dport = ((int)pkt[hlen - 2] << 8) | pkt[hlen - 1];
        dlen = (int)n - hlen;
        /* M1 仅服务 DNS（53）；QUIC 等直接丢弃，让应用回落 TCP */
        if (dlen < 12 || dlen > DNS_QUERY_MAX || dport != 53)
            continue;

        g_dns_seen++;
        if (g_dns_seen <= 5)
            logi("udp dns #%d len=%d", g_dns_seen, dlen);

        {
            struct uq *q = calloc(1, sizeof *q);
            pthread_t t;
            int allowed;
            if (!q)
                continue;
            pthread_mutex_lock(&g_cnt_lock);
            allowed = g_dns < MAX_DNS;
            if (allowed)
                g_dns++;
            else
                g_drop_dns++;
            pthread_mutex_unlock(&g_cnt_lock);
            if (!allowed) {
                free(q);
                continue;
            }
            memcpy(&q->src, &ss, (size_t)sl);
            q->slen = sl;
            q->hlen = hlen;
            memcpy(q->hdr, pkt, (size_t)hlen);
            q->dnslen = dlen;
            memcpy(q->dns, pkt + hlen, (size_t)dlen);
            if (pthread_create(&t, &g_attr, dns_fwd, q) != 0) {
                pthread_mutex_lock(&g_cnt_lock);
                g_dns--;
                pthread_mutex_unlock(&g_cnt_lock);
                free(q);
            }
        }
    }
    return NULL;
}

/* ---------- main：TCP listen + UDP bind ---------- */

int main(int argc, char **argv)
{
    int ls, one = 1;
    struct sockaddr_in sa;
    pthread_t t;

    if (argc < 4) {
        fprintf(stderr,
                "usage: %s <listen_port> <up_host> <up_port> [up_user] [up_pass] [resolver_ip]\n",
                argv[0]);
        return 2;
    }
    g_up_host = argv[2];
    g_up_port = atoi(argv[3]);
    if (g_up_port < 1 || g_up_port > 65535) {
        fprintf(stderr, "bad up_port\n");
        return 2;
    }
    if (argc > 4)
        g_up_user = argv[4][0] ? argv[4] : "";
    if (argc > 5)
        g_up_pass = argv[5][0] ? argv[5] : "";
    if (argc > 6 && argv[6][0]) {
        char *p = argv[6];
        g_nres = 0;
        while (*p && g_nres < MAX_RESOLVERS) {
            char *comma = strchr(p, ',');
            size_t len = comma ? (size_t)(comma - p) : strlen(p);
            if (len > 0 && len < sizeof g_resolvers[0]) {
                memcpy(g_resolvers[g_nres], p, len);
                g_resolvers[g_nres][len] = 0;
                g_nres++;
            }
            if (!comma)
                break;
            p = comma + 1;
        }
    }
    if (g_nres == 0) {
        strcpy(g_resolvers[0], "114.114.114.114");
        g_nres = 1;
    }
    strncpy(g_resolver, g_resolvers[0], sizeof g_resolver - 1);
    g_resolver[sizeof g_resolver - 1] = 0;

    /* argv[7] = eth0 网络句柄；绑定后 relay 的上游连接不再进入 tun */
    if (argc > 7 && argv[7][0]) {
        g_net = (net_handle_t)strtoull(argv[7], NULL, 10);
        if (g_net && android_setprocnetwork) {
            int rc = android_setprocnetwork(g_net);
            logi("bind upstream: handle=%llu rc=%d", (unsigned long long)g_net, rc);
        } else if (g_net) {
            logi("bind upstream: android_setprocnetwork unavailable");
        }
    }

    {
        int p = atoi(argv[1]);
        if (p < 1 || p > 65535) {
            fprintf(stderr, "bad listen_port\n");
            return 2;
        }
        g_port = p;
    }

    signal(SIGPIPE, SIG_IGN);
    pthread_attr_init(&g_attr);
    pthread_attr_setdetachstate(&g_attr, PTHREAD_CREATE_DETACHED);
    pthread_attr_setstacksize(&g_attr, 96 * 1024);

    /* 提升 fd 软上限：长连接较多时避免 "Too many open files" */
    {
        struct rlimit rl;
        if (getrlimit(RLIMIT_NOFILE, &rl) == 0) {
            rlim_t want = FD_SOFT_LIMIT;
            if (rl.rlim_max != RLIM_INFINITY && want > rl.rlim_max)
                want = rl.rlim_max;
            if (rl.rlim_cur < want) {
                rl.rlim_cur = want;
                if (setrlimit(RLIMIT_NOFILE, &rl) != 0)
                    logi("setrlimit(NOFILE) failed: %s", strerror(errno));
            }
            logi("fd limit: cur=%ld max=%ld", (long)rl.rlim_cur, (long)rl.rlim_max);
        }
    }

    /* UDP 数据面 */
    g_udp = socket(AF_INET, SOCK_DGRAM, 0);
    if (g_udp < 0) {
        logi("udp socket: %s", strerror(errno));
        return 1;
    }
    memset(&sa, 0, sizeof sa);
    sa.sin_family = AF_INET;
    sa.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    sa.sin_port = htons((unsigned short)g_port);
    if (bind(g_udp, (struct sockaddr *)&sa, sizeof sa) != 0) {
        logi("udp bind 127.0.0.1:%d: %s", g_port, strerror(errno));
        return 1;
    }
    if (pthread_create(&t, &g_attr, udp_loop, NULL) != 0) {
        logi("udp thread failed");
        return 1;
    }

    /* TCP 数据面 */
    ls = socket(AF_INET, SOCK_STREAM, 0);
    if (ls < 0) {
        logi("tcp socket: %s", strerror(errno));
        return 1;
    }
    setsockopt(ls, SOL_SOCKET, SO_REUSEADDR, &one, sizeof one);
    if (bind(ls, (struct sockaddr *)&sa, sizeof sa) != 0 || listen(ls, 16) != 0) {
        logi("tcp bind/listen 127.0.0.1:%d: %s", g_port, strerror(errno));
        return 1;
    }
    {
        char reslist[220] = "";
        int ri;
        for (ri = 0; ri < g_nres; ri++) {
            if (ri) strncat(reslist, ",", sizeof reslist - strlen(reslist) - 1);
            strncat(reslist, g_resolvers[ri], sizeof reslist - strlen(reslist) - 1);
        }
        logi("ready 127.0.0.1:%d -> %s:%d%s resolvers=%s", g_port, g_up_host, g_up_port,
             g_up_user[0] ? " (auth)" : "", reslist);
    }

    for (;;) {
        int c = accept(ls, NULL, NULL);
        int allowed;
        if (c < 0) {
            if (errno == EINTR)
                continue;
            logi("accept: %s", strerror(errno));
            usleep(50000);
            continue;
        }
        pthread_mutex_lock(&g_cnt_lock);
        allowed = g_clients < MAX_CLIENTS;
        if (allowed)
            g_clients++;
        else
            g_drop_clients++;
        pthread_mutex_unlock(&g_cnt_lock);
        if (!allowed) {
            close(c);              /* 超并发上限：直接断开，由上层重试 */
            continue;
        }
        if (pthread_create(&t, &g_attr, tcp_client, (void *)(long)c) != 0) {
            pthread_mutex_lock(&g_cnt_lock);
            g_clients--;
            pthread_mutex_unlock(&g_cnt_lock);
            close(c);
        }
    }
}
