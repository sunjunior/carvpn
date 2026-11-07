# vendored: hev-socks5-tunnel

- 源码包：`third_party/hev-socks5-tunnel-0c68850.tar.gz`（0.94 MB，内置全部子模块）
- 对应上游版本：`0c6885060c27134d1bf6a26b1132b6c90f80e4a4`（2026-11-03，含 lwip 8115cd6）
- SHA256：`AEF4C3ABB9614C17BE3A758260F4B40FD557A33A99C16D59871C18E0101CF10E`
- 解包脚本：`scripts/fetch_deps.sh`，解压到 `app/src/main/cpp/hev-socks5-tunnel/`（该目录 gitignore，不入库）
- 为什么用压缩包而不是 clone：上游重写历史后，旧 commit 的对象已不可读（`fatal: unable to read tree`），固定 SHA 无法稳定复现；压缩包保证 CI 与本地构建一致

## Q1 嵌入接口定案（上游官方 JNI 存在，弃用自研 wrapper）

- JNI 绑定：`src/hev-jni.c`（`#ifdef ANDROID` + `ENABLE_LIBRARY`），`JNI_OnLoad` → `FindClass(PKGNAME "/"/CLSNAME)`→`RegisterNatives`；
- Java 类（我们自己的）：`com.sunjunior.caresimvpn.CarVpnTunnel`，四个 static native：
  - `TProxyStartService(String config_path, int fd) → boolean`
  - `TProxyStopService() → boolean`（**有官方 stop，善用**）
  - `TProxyIsRunning() → boolean`
  - `TProxyGetStats() → long[]`
- 下层入口：`hev_socks5_tunnel_main(config_path, tun_fd)`；**JNI 传的 `fd` 直接生效**（`hev_socks5_tunnel_init(tun_fd)`），config 里无需 `tunnel.name`；
- `PKGNAME/CLSNAME` 编译期覆盖（`Application.mk` 的 `APP_CFLAGS`）。

## Q2 配置字段定案（hev-config.c / README 全量核对）

```yaml
tunnel:
  mtu: 1400            # 有 fd 时不需要 name
socks5:
  address: 127.0.0.1   # M1 = 本地 relay
  port: 8101
  udp: 'udp'           # 经典 UDP ASSOCIATE（连本 relay，掌控全部行为）
  # udp-address: ''    # override 用不到（relay 在本机）
  # username/password  # M1 不需要（本地无鉴权）
# mapdns:              # 关闭（YAGNI）
misc:
  log-file: stderr
  log-level: info
```
- socks5 支持字段：address/port/udp('tcp'|'udp')/udp-address/username/password/mark/pipeline/tcp-fastopen；
- udp-in-tcp 模式需要上游 socks5 服务器实现 hev 私有扩展——对本机 relay 无意义（relay 自实现 UDP 内存应答），保持 'udp'。
  
## Q3 构建定案（改用 ndk-build 链路，计划中的 CMake 方案作废）

- 上游自带 `Android.mk`（AAR 库变体，含 `-DENABLE_LIBRARY`、`-DFD_SET_DEFINED -DSOCKLEN_T_DEFINED`、armv7a 加 `-mfpu=neon`）与 `build.mk`（源清单）；
- AGP 通过 `externalNativeBuild { ndkBuild { path 'src/main/jni/Android.mk' } }`；
- 我们的 `src/main/jni/Application.mk`：`APP_PLATFORM=android-21`（上游写 android-29，车机是 23，必须降）+ `APP_ABI=armeabi-v7a` + `APP_CFLAGS += -DPKGNAME=com/sunjunior/caresimvpn -DCLSNAME=CarVpnTunnel`；
- 我们的 `src/main/jni/Android.mk`：仅 `include 上游 Android.mk`（其 `my-dir` 自解析，子模块静态库内部自拼）；
- 产物：`libhev-socks5-tunnel.so`（launch 时 `System.loadLibrary`）。

## 与上游差异/备注

- 上游 `Application.mk` 是 android-29：我们覆盖为 android-21（NDK 自带 target 21 平台头，编译不受影响；车机 API 23）；如个别符号因 API 21 缺失报错，补 `-DBACKPORT_xxx` 而不是改 ABI；
- 子模块固定经由主仓 SHA 锚定（recursive 克隆其默认分支的联动 commit）。

## 实测记录

- 2026-11-04 任务 0 详见 `docs/probe-results.md`：主上游=http CONNECT(raw)；DNS=dnsu2t（本地 relay）；UDP 上游死路（与 udp-in-tcp 无关）。
