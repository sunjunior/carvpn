# CarVPN

面向 Android 车机的按应用 VPN 客户端：系统级 `VpnService` + 本地 SOCKS5 relay，把指定应用的流量转发到订阅里的 HTTP CONNECT 代理节点。

在别克英朗车机（AC8317 / MT6735 / Android 6.0 / armeabi-v7a）上实机验证，用于让 QQ 音乐等应用通过代理正常联网。

- **版本**：0.2.0（versionCode 2）
- **最低系统**：Android 5.0（API 21），实机验证 Android 6.0（API 23）
- **ABI**：仅 `armeabi-v7a`
- **依赖**：[hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel)（源码包随仓库分发，构建时解压）

> 仓库内不包含任何订阅链接、节点地址、账号密码或签名密钥；节点数据只保存在车机应用私有目录中。

## 下载

编译好的 APK 发布在 [Releases](https://github.com/sunjunior/carvpn/releases) 页面，当前版本 [v0.2](https://github.com/sunjunior/carvpn/releases/tag/v0.2)。安装包由 GitHub Actions 在打标签时自动构建。

## 功能

- **订阅导入**：解析 Clash / 通用订阅 YAML，只取 `type: http` 节点，自动识别 `tls: true`
- **多节点管理**：下拉选择、协议标注（`· TLS` / `· 明文`）、持久化
- **自动选路**：TCP 预筛 + 分批「TLS 握手 + CONNECT 200」完整探活，按时延选最快可用节点
- **故障切换**：运行期看门狗检测节点健康，连续失败自动切换；上游连续拒绝也会触发切换
- **按应用代理**：只代理白名单包名，其余应用不受影响
- **出口网络自适应**：有 WiFi 走 WiFi，没有 WiFi 走 eth0；relay、TLS 适配器、探活统一绑定同一出口
- **隧道内 DNS**：UDP/53 转 DNS-over-TCP 经节点转发，带污染检测与多解析器回退
- **加密上游**：`tls: true` 节点由本地 TLS 适配器终结外层 TLS，链路上看不到内层 SNI

## 工作原理

```text
被代理应用 (QQ音乐 / 浏览器 / ...)
        │  普通 socket
        ▼
VpnService tun: 26.26.26.1/32, DNS 26.26.26.2, route 0.0.0.0/0, MTU 1400
        │  tun fd (JNI)
        ▼
hev-socks5-tunnel  ──  SOCKS5  ──▶  127.0.0.1:8101 (native relay)
                                      │
                     ┌────────────────┴─────────────────┐
                     │ TCP                              │ UDP/53
                     ▼                                  ▼
          HTTP CONNECT 上游                   DNS-over-TCP（2 字节长度前缀）
                     │                                  │
        tls:true ?  TlsUpstream（本地端口）              │
                     │  TLS(SNI=节点域名)               │
                     └────────────────┬─────────────────┘
                                      ▼
                                  代理节点 ──▶ 目标站点
```

关键点：

- **relay 与探活 socket 必须绑定真实出口**：ROM 的 VPN 是全局的（无 uidrange），不绑定会把上游连接套进自己的 tun，形成回环。出口选择顺序为 WiFi → eth0 → 蜂窝 → 其它可用网络（优先系统验证过的网络）。
- **`tls: true` 节点走 `TlsUpstream`**：relay 只会说明文 CONNECT，而订阅节点是 HTTPS 代理。适配器监听 `127.0.0.1:<临时端口>`，对节点做 TLS（SNI=节点域名）后转发 CONNECT，再双向透传。
- **DNS 不直连**：tun 内的 DNS 请求由 relay 通过 `CONNECT resolver:53` 转成 DNS-over-TCP，避免 UDP 被链路丢弃，并对 `198.18.0.0/15`、`0.0.0.0` 等污染结果换解析器重试。
- **配置固定单文件**：`cache/hev_current.yml` 每次启动覆盖，启动/退出都会清理历史文件，不会随启动次数增长。

## 目录结构

```text
.
├── app/
│   ├── build.gradle
│   └── src/
│       ├── main/
│       │   ├── java/com/sunjunior/caresimvpn/
│       │   │   ├── MainActivity.java        # UI：订阅、节点、白名单、启停
│       │   │   ├── EsimVpnService.java      # VpnService、relay/隧道生命周期、故障切换
│       │   │   ├── TlsUpstream.java         # TLS 上游适配器
│       │   │   ├── NodeStore.java           # 多节点持久化与编解码
│       │   │   ├── NodeConfig.java          # 单节点参数校验（兼容旧版）
│       │   │   ├── NodeProbe.java           # TCP / TLS + CONNECT 探活
│       │   │   ├── SubImporter.java         # Clash 订阅解析
│       │   │   ├── HttpFetcher.java         # 带证书与网络绑定的 HTTP(S) 拉取
│       │   │   ├── WhitelistStore.java      # 白名单存储
│       │   │   ├── CarVpnRelay.java         # relay 可执行文件常量
│       │   │   └── CarVpnTunnel.java        # hev-socks5-tunnel JNI
│       │   ├── jni/                         # relay 源码与 ndk-build 配置
│       │   └── res/                         # 布局、图标、extra_roots.pem
│       └── test/                            # JVM 单元测试
├── docs/                                    # 设计、依赖说明、历史探针记录
├── scripts/
│   ├── fetch_deps.sh                        # 解压内置的 hev-socks5-tunnel 源码包
│   └── build-release.{sh,bat}               # release 构建 + 签名
├── gradlew / gradlew.bat                    # Gradle Wrapper 8.9
└── .github/workflows/build.yml              # CI：单测 + debug APK
```

## 构建

### 环境

- JDK 17
- Android SDK：`platforms;android-34`、`build-tools;34.0.0`
- NDK：`26.3.11579264`
- 环境变量：`ANDROID_HOME`（或 `ANDROID_SDK_ROOT`）

### 解压原生依赖

```bash
./scripts/fetch_deps.sh
```

脚本会把 `third_party/hev-socks5-tunnel-0c68850.tar.gz` 解压到 `app/src/main/cpp/`（该目录已被 `.gitignore` 忽略）。不再依赖上游仓库，版本与 SHA256 见 [docs/VENDORED.md](docs/VENDORED.md)。

### 单元测试与 Debug 构建

```bash
./gradlew test
./gradlew assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`。

> 首次执行 `gradlew` 会下载 Gradle 8.9 发行包（约 130MB）。如果网络较慢，可以直接使用本机已安装的同版本 `gradle`（命令相同），或交给 GitHub Actions 构建。


### Release 构建与签名

签名材料不进入仓库，通过环境变量传入：

```bash
export KEYSTORE=/path/to/release.jks
export KS_PASS='***'
export KEY_PASS='***'
export KS_ALIAS=carvpn            # 可选
./scripts/build-release.sh        # Windows: scripts\build-release.bat
```

产物默认输出到仓库根目录 `carvpn.apk`。脚本会先编译 relay、拷入 `jniLibs`，再 `assembleRelease`、`zipalign`、`apksigner` 签名（v1 + v2）。

> 签名变更后覆盖安装会失败，需要先卸载旧版本。

## 使用

1. 安装 APK 并打开应用，首次启动授予 VPN 权限。
2. 填入 Clash / 通用订阅链接，点「导入订阅」；只导入 `type: http` 的节点。
3. 勾选「自动选择最快可用节点」会立即探活并选路；也可直接在下拉框手动选择。
4. 在「分流白名单包名」中填写需要代理的应用包名，逗号 / 空格分隔，例如：

   ```text
   com.tencent.qqmusiccar, com.tencent.qqlive.audiobox, com.mmbox.xbrowser
   ```

   白名单实时保存，修改后重启代理生效。
5. 点「启动代理」。运行期间可在通知栏停止，或回到应用点「停止代理」。

常用排查命令：

```bash
adb logcat -s carvpn carvpn-relay
adb shell dumpsys connectivity | grep -i vpn
```

`carvpn` 标签下可以看到选中的节点、TLS 适配器端口、逐节点探活结果（`tcp=…ms full=…ms`）；`carvpn-relay` 是原生 relay 的日志。

## 已知限制

- 只支持 HTTP CONNECT 上游（`type: http`）；`socks5` / `ss` / `vmess` / `tuic` 等节点会在导入时跳过。
- 除 DNS 外的 UDP 不转发，走 QUIC（UDP 443）的应用会回退到 TCP，首次连接可能偏慢。
- 仅打包 `armeabi-v7a`，不适用于 64 位设备。
- `targetSdk 23` 是为车机 Android 6.0 兼容性固定，不满足 Google Play 的上架要求。
- 节点可用性依赖出口链路：同一订阅里可能有部分地区节点的 TLS 握手被链路丢弃，自动选路会自动避开。
- 可播放内容、推荐内容受节点出口地区与版权授权影响，属于内容侧限制，不是隧道故障。

## 安全说明

- 订阅链接、节点账号密码只保存在应用私有 `SharedPreferences`（`node_store`），不会写入源码或日志。
- `res/raw/extra_roots.pem` 仅包含公共根证书（补齐 Android 6 缺失的 ISRG Root），不含私钥。
- 发布用 keystore 放在仓库外，由构建脚本通过环境变量读取。
- 原生依赖以源码包形式随仓库分发，构建前可核对 [docs/VENDORED.md](docs/VENDORED.md) 中的 SHA256。

## 文档

- [docs/VENDORED.md](docs/VENDORED.md)：原生依赖版本、JNI 接口与配置字段
- [docs/probe-results.md](docs/probe-results.md)：早期节点探测记录（历史资料，结论以代码与 README 为准）

## 致谢

- [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel)（MIT）及其子模块：tun 转发与 SOCKS5 客户端
- [lwIP](https://savannah.nongnu.org/projects/lwip/)：轻量 TCP/IP 协议栈

## 许可

本项目采用 **GNU General Public License v3.0（GPLv3）**，完整条款见 [LICENSE](LICENSE)。

第三方组件 `hev-socks5-tunnel` 采用 MIT 协议，与 GPLv3 兼容；分发包含该组件的二进制时，需保留其版权与许可声明（见 [docs/VENDORED.md](docs/VENDORED.md)）。
