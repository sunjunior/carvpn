# 任务 0 实测记录：节点形态探测（2026-11-04，PC 端）

> 安全说明：本档案**不含**订阅 URL、节点域名、凭据（上游研究红线 R2）。节点以形态代号记录。原始数据与脚本在本地 `/tmp/opencode`，不入库。

## 订阅拆解快照

- 订阅为 Clash YAML；节点总数 30，M1 范围内 **24 个**（http 21 / socks5 3；ss 0；tuic/vless/hysteria2 9 个超出 D4 支持面，忽略）。
- 凭据全网统一（同一 username/password），代理目标端口策略统一。
- 订阅里 http/socks5 节点标 `tls: true`；实测发现 **TLS 是可选项而非必须**（部分节点 raw 直接可用）。

## 实测矩阵（形态 × 结果）

| 形态（脱敏代号） | 类型/端口 | raw 握手 | TLS 握手 | CONNECT 数据面 | 备注 |
|---|---|---|---|---|---|
| http-西1 | http/443 | n/a | ✅ | ✅ CONNECT qq.com:80 → 200 → HTTP 真回包；**⚠️ 稍后复测：该节点 raw 亦通** | **M1 首选** |
| http-洛CM | http/5228 | n/a | - | raw 回 TLS alert；**仅 TLS 后可用** | 备选，M1 不用（无 TLS 依赖则弃） |
| socks5-西2 / 硅谷 / 港2 | socks5/443 | ✅ 握手+认证 OK | ✅ 同样 OK | ❌ **CONNECT rep=4（目标受限）** | 弃用：UDP ASSOCIATE 回包 `BND=0.0.0.0:0` 不规范 |

## 关键结论（决定 M1 分支）

1. **上游可用型态=M1 主上游**：http CONNECT 节点（raw, 无 TLS）⇒ relay 无 TLS 依赖。
2. **TCP 目标端口 allowlist：80 / 443 / 53**；8080/12345/10050 **全部超时被拒**。
3. **DNS-over-TCP 经 CONNECT 实测可达**：CONNECT `114.114.114.114:53` 与 `119.29.29.29:53`、`8.8.8.8:53` 均成功，并对 `qq.com` A 查询**成功回包**（长度前缀封装）⇒ 车机端 `.qq.com` 域名级 DNS 丢弃被完全绕开。
4. **UDP 端到端死路**：上游 socks5 节点 ASSOCIATE 握手应答 OK，但 `BND=0.0.0.0:0` + DNS 数据面超时（3/3 节点）→ **原计划的"ss-local 提前"回退分支作废**（且订阅中 ss 节点数为 0），改为 **relay 内置 DNS→TCP 转发器（dnsu2t 模式）**。

## M1 架构增量（相对原计划的修订）

- hev-socks5-tunnel 仍是 tun 层（任务 4/5），但其上游改为**本地 relay（`127.0.0.1:8101`）**；
- 新增自研小进程 `carvpn-relay`（C，预计 ~500 行）：
  - **TCP**：本地 socks5 服务端 → 经上游 http CONNECT 透传（支持 Basic 认证）；
  - **UDP/53**：解析 DNS 报文 → 经上游 CONNECT `resolver:53` 做 DNS-over-TCP（2 字节长度前缀）→ 以 UDP 应答给 tun；
- 风险更新（规格 §9 RS1 的落地版）：中端盒对**平文 CONNECT+TLS 未知号形态**（TLS 443 外层+ TLS 内层双包形态）已从形态上避开（M1 raw）；等 M1 上车后再评估加 TLS 外层的必要性。

## 待验证分支（M1 上车时确认）

- 中端盒对"443 端口裸 TCP"？PC(家宽)与车机(eSIM)链路不同，**西雅图 443 裸 socks5/http 上的 raw 会话是否能在 eSIM 下也活** = M1 判据⑤的内容之一；若不通，转用 TLS 节点形态（需给 relay 加 mbedtls，架构惰 Push，已有预案）。
