# 故障排查手册（TROUBLESHOOTING）

> 按**症状**索引。每条给出：原因 → 如何确认 → 如何解决。
>
> 相关：[TESTING.md](TESTING.md)（如何复现）、[ARCHITECTURE.md](ARCHITECTURE.md)（链路细节）、[MODULES.md](MODULES.md)（类契约）

---

## 0. 先做这三件事

排查任何问题前，先拿到证据。

### 0.1 看日志

```bash
# 实时日志（所有应用日志的 tag 都是 ProotTerm）
adb logcat -c && adb logcat -s ProotTerm:V

# 导出日志文件（最完整，含滚动归档）
adb shell run-as com.li63050a.linuxandroid cat files/logs/app.log
adb shell run-as com.li63050a.linuxandroid cat files/logs/app.log.old
```

应用内也可直接看：**抽屉 → 日志**（可复制、可分享）。

### 0.1.1 按标签过滤日志

日志行格式为 `yyyy-MM-dd HH:mm:ss.SSS | 级别 | 标签 | 消息`。**应用内日志页的搜索框**按行过滤，直接搜标签名即可（例如输入 `BootReceiver`）；命令行则用：

```bash
adb logcat -s ProotTerm:V | grep "BootReceiver"
adb shell run-as com.li63050a.linuxandroid cat files/logs/app.log | grep "RootfsManager"
```

常用标签与它们负责回答的问题：

| 标签 | 记录什么 | 排查时看它 |
| --- | --- | --- |
| `AppLogger` / `App` | 日志系统自身、应用启动（SDK 版本） | 日志页空白、启动即崩 |
| `SessionManager` | 会话状态机：`started <id> @ <location> limits=…` / `exit id=… code=…` / `prepare(<id>): destroying previous session of this instance` / `forgot instance`（旧会话单例的 tag 已不存在） | **会话为什么没了**（§5.9）、某个实例为什么没起来（§5.15）、多实例并存（§5.16） |
| `ProotService` | 前台服务：`stop requested`、`already running`、`version … not in manifest/not installed, abort`、`auto started`、`onDestroy`、`session ended, stopping service` | **开机自启**（§5.10）、**通知消失但会话还在**（§5.12） |
| `ProotSession`（日志 tag 为 `Proot`） | 启动命令、环境变量、`send:`、`exit … code=`、读循环结束 | 启动失败（§2.1/§2.7）、命令是否真的写进进程（§5.3） |
| `RootfsManager` | `location -> …`、`location unavailable: …`、`finalize` / `uninstall` / `removed part` | **存储位置与降级**（§2.8–§2.10） |
| `BootReceiver` | 开机广播是否到达、是否因开关/目标缺失被跳过 | **开机不自启**（§5.10） |
| `MainActivity` | 页面装载、`myApps=… running=…`、切换会话、清单加载失败、通知权限申请失败 | UI 状态不对、清单问题 |
| `Downloader` / `Extractor` / `Install` | 下载、解压、安装全流程 | 第 3、4 节 |
| `MultiPart` | `HEAD len=…`、`multipart id=… total=… threads=…`、`chunk[a-b] reused`、`single-thread (total=…)`、`falling back to N other mirror(s)` | **分块下载与降级**（§3.7–§3.9） |
| `MirrorProbe` | `ranked: url=…ms`、`unreachable: url(原因)`、`total timeout, falling back to manifest order`、`no reachable mirror, keep manifest order` | **测速慢 / 下载卡在「正在测速」**（§3.7） |
| `AptSwitch` | `deb822 rewritten xN`、`legacy rewritten`、`alpine rewritten xN`、`备份已存在，保留原始备份`、`跳过无法识别的 apk 仓库行` | **换源结果与恢复**（§3.10–§3.12） |
| `RootfsManager` | `removed part <dir>/<name>`（含分块残留）、`uninstall` | **`.part.N` 残留**（§3.9） |
| `InstanceStore` | `读取 instances.json 失败，回退为空配置`、`pruned N stale config(s)`、`写入 instances.json 失败` | **实例配置丢失/重置**（§5.16） |

> 注意：**终端输出不写日志**（有意为之，交互式命令会刷爆 1MB 的 `app.log` 并挤掉真正有用的启动/失败信息）。要看命令输出请用终端页输出区或 `LogActivity` 的分享，别指望 `app.log` 里有回显。

### 0.2 看文件布局

```bash
adb shell run-as com.li63050a.linuxandroid ls -l files/
```

期望结构：

```
files/
  rootfs/<id>/            最终 rootfs（含 .installed 才算法「已安装」）
  rootfs/<id>.old/        升级安装的回滚备份（正常情况不存在）
  tmp_<id>/               解压临时目录（正常情况不存在）
  <id>.<format>.part      断点续传文件
  <id>.<format>.part.N    多线程分块临时文件（N = 0…7；正常情况不存在，见 §3.9）
  <id>.<format>.part.N.src  分块来源标记（内容 url|total），保证跨镜像不复用
  <id>.<format>.part.merge  合并中间文件（正常情况不存在）
  instances.json          每实例配置：资源限制 / 镜像偏好 / 下载线程数 / 换源记录
  native/                 libtalloc.so.2、libandroid-shmem.so
  proot_tmp/              PROOT_TMP_DIR
  logs/app.log
```

> `instances.json` **固定在内部 `filesDir`**，不随存储位置变化（与 `native/`、`proot_tmp/`、`logs/` 同理）。若要手工确认某一实例的资源限制/镜像设置，直接看这个文件即可。

### 0.3 看设备 ABI

```bash
adb shell getprop ro.product.cpu.abi
```

**必须是 `arm64-v8a`。** 见 §2.1 —— 这是本项目最高频的「看起来是 bug 其实是环境」问题。

---

## 1. 构建期问题

### 1.1 `Unsupported class file major version`

**原因**：JDK 版本不匹配。AGP 8.11.1 需要 **JDK 17**。

```bash
java -version          # 必须是 17.x
./gradlew -version     # 确认 Gradle 用的 JVM
```

**解决**：装 JDK 17 并让 `JAVA_HOME` 指向它。

```bash
export JAVA_HOME=/path/to/jdk-17
export PATH="$JAVA_HOME/bin:$PATH"
```

> JDK 21 有时能跑但不在支持矩阵内；JDK 8/11 一定失败。

### 1.2 `SDK location not found`

**原因**：未配置 Android SDK 路径。

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
# 或写入 local.properties（已 gitignore）
echo "sdk.dir=$HOME/Android/Sdk" >> local.properties
```

### 1.3 `Failed to install ... platform 36` / AAPT 报找不到资源

**原因**：缺 SDK 组件。

```bash
sdkmanager "platforms;android-36" "build-tools;36.0.0"
yes | sdkmanager --licenses
```

### 1.4 AAPT 报「属性未找到」

**原因**：XML 里用了不存在的属性名。历史上 `item_distro.xml` 踩过这个坑。

**解决**：确认属性在对应组件的命名空间下真实存在。特别注意：

- AppCompat/Material 组件属性在 `app:` 命名空间（`xmlns:app="http://schemas.android.com/apk/res-auto"`）
- 原生属性在 `android:` 命名空间
- `tools:` 命名空间必须在**根元素**声明，不能写在子元素上

### 1.5 Gradle 下载卡住 / 超时

首次构建需下载 Gradle 8.13 与全部依赖。

```bash
# 项目已配 org.gradle.parallel=true；可加镜像（~/.gradle/init.gradle）
# 或直接重试（Gradle 支持断点）
./gradlew assembleDebug --info
```

---

## 2. 安装与启动问题

### 2.1 ⚠️ 应用装上了，但点「启动」没反应 / PRoot 启动失败

**最常见原因：设备/模拟器不是 arm64。**

本项目 `abiFilters` 只有 `arm64-v8a`，且 `libproot.so` 是 **aarch64 ELF 可执行文件**。在 x86_64 模拟器上，APK 能装、UI 能开，但 `libproot.so` 无法执行。

```bash
adb shell getprop ro.product.cpu.abi
# arm64-v8a  -> 正常
# x86_64     -> 这就是原因
```

**解决**：

- 用 **arm64 真机**（推荐）
- 或用 **arm64 系统镜像**的模拟器（Apple Silicon Mac 上选 `arm64-v8a` 镜像）

**确认方法**：日志里会出现

```
[ProotTerm] 启动失败: ...
```

且 `adb shell ps -A | grep proot` 无输出。

### 2.2 `缺少 PRoot 二进制: libproot.so`

**原因**：`jniLibs` 里的 `.so` 没打进 APK，或未解压到 `nativeLibraryDir`。

```bash
# 1) 确认文件在仓库里
ls -l app/src/main/jniLibs/arm64-v8a/

# 2) 确认进了 APK
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep '\.so'

# 3) 确认安装后解压到了设备
adb shell run-as com.li63050a.linuxandroid ls -l lib/
```

**解决**：

- 若第 2 步缺失 → 检查 `app/build.gradle.kts` 的 `packaging { jniLibs { useLegacyPackaging = true } }` 是否还在
- 若第 3 步缺失 → 说明没解压。必须保证 `useLegacyPackaging = true`（AGP 会据此合成 `android:extractNativeLibs="true"`）
- 若文件本身不在 → 按 `app/src/main/jniLibs/arm64-v8a/README.txt` 从 Termux deb 重新提取

### 2.3 `缺少 PRoot loader: libproot-loader.so`

同 §2.2。注意 loader 是**独立文件**，不是 `libproot.so` 自带的。

### 2.4 `rootfs 不存在` / 点启动提示未安装

**原因**：`rootfs/<id>/.installed` 标记不存在。**「已安装」的判据是标记文件，不是目录存在性。**

```bash
adb shell run-as com.li63050a.linuxandroid ls files/rootfs/
adb shell run-as com.li63050a.linuxandroid ls files/rootfs/alpine-3.20/.installed
```

**解决**：

- 首次安装未完成 → 重新下载安装
- 目录在但标记没了（调试时手工删过）→ 重新安装，或手工 `touch` 标记（内容完好时）
- 反过来，想**强制 App 认为未安装**（保留已解压内容调试）→ 删掉 `.installed` 即可

### 2.5 PRoot 启动了，但 shell 无提示符、无回显

**这不是 bug，是预期行为。**

应用用的是**非 PTY** 管道，`sh` 处于非交互模式：不打印提示符、不回显输入。UI 用本地回显 `$ <命令>` 来补偿。

**解决**：想要完整交互体验，启动后输入：

```
sh -i
```

（README 与终端页启动横幅都提示了这一点。）

### 2.6 `libtalloc.so.2` / `libandroid-shmem.so` 找不到

**原因**：`NativeDeps` 未释放 assets 运行库，或 `LD_LIBRARY_PATH` 未生效。

```bash
# 确认运行库已释放
adb shell run-as com.li63050a.linuxandroid ls -l files/native/
# 期望：libtalloc.so.2、libandroid-shmem.so

# 确认环境变量注入（日志里有专门一行）
adb logcat -s ProotTerm:V | grep "env PROOT_TMP_DIR"
```

**背景**：`libproot.so` 的 RUNPATH 硬编码为 `/data/data/com.termux/files/usr/lib`（Termux 私有目录），在其他设备上不存在，所以必须通过 `LD_LIBRARY_PATH` 指向 `filesDir/native`。

**注意**：这两个文件名**不以 `.so` 结尾**（`libtalloc.so.2`），不会被打进 `jniLibs`，所以只能放 `assets/` 并由运行时释放 —— 这是有意设计，不要试图移到 `jniLibs`。

### 2.7 装在「外部存储」的系统启动就失败（权限被拒绝）—— Android 11+ 平台限制

**症状**：设置页把存储位置选成「外部存储」、安装完成后点「启动」，弹出如下提示（`ProotSession.explainStartFailure()` 的翻译结果）：

```
无法执行 PRoot 二进制（权限被拒绝）。
若 rootfs 安装在「外部存储 /Android/data」，该位置在 Android 11+ 上
通常不允许执行文件。请在设置页把存储位置改为「内部存储」后重新安装。
原始错误：... Permission denied ...
```

也可能表现为启动后进程立刻死掉、`adb shell ps -A | grep proot` 无输出。

**原因**：`/Android/data/<pkg>` 在 **Android 11（API 30）及以上**由 **FUSE** 提供，该挂载点**通常带 `noexec` 语义**——可以读写，但**不能执行其中的文件**。proot 需要从 rootfs 里 `exec` guest 二进制（`/bin/sh` 等），于是在外部位置必然失败。

**这是 Android 平台的行为，不是本应用的 bug，也没有代码层面的修法。**

**解决办法（唯一的可用路径）**：把系统重新装到内部存储。

1. 设置页 → **存储位置 → 内部存储**
2. 到「我的系统」/版本页对目标系统点**重新下载**（切换位置**不会**迁移已装数据，见 §2.8）
3. 装完后再启动

装完后，`filesDir`（`/data/data/<pkg>`）是真实文件系统，可正常执行。

**关于「我选外部就是因为它容量大」**：这个诉求在当前约束下无法满足。外部位置能解决的是容量，但代价是不能运行；而运行是本应用的核心功能。

```bash
# 自行验证 noexec（有 adb 与 root/debug 包时）
adb shell mount | grep -i "/data/media\|/mnt/user\|fuse"
# 观察挂载选项里是否有 noexec / nosuid
```

> `native/`、`proot_tmp/`、`logs/` **固定在内部**（§8.14），不受这个选择影响 —— 这也是为什么即使选了外部位置，应用启动本身仍然正常、只有 rootfs 跑不起来。

### 2.8 改了存储位置，系统列表就空了 / 版本显示「已安装在…」

**数据没有丢，只是换了个目录找。**

两个位置是**各自独立的两套目录**：

| 位置 | 路径（`<pkg>` = `com.li63050a.linuxandroid`） |
| --- | --- |
| 内部 | `/data/data/com.li63050a.linuxandroid`（设备上常为 `/data/user/0/...`） |
| 外部 | `/storage/emulated/0/Android/data/com.li63050a.linuxandroid` |

切换存储位置**只影响后续安装**，不做任何迁移（跨文件系统 `renameTo` 不可靠，逐文件复制几十万小文件既慢又可能中断）。因此切过去看不到原来装的系统是**预期行为**。

**界面已如实提示**，若没看到提示就是这两个地方的问题：

- 「我的系统」页顶部的「另有 N 个系统安装在『XX』…」横幅（`banner_other_location` / `tv_banner_other_location`），带一键切换
- 版本列表里该版本显示为 `State.InstalledElsewhere`（文案「已安装在『XX』，切换存储位置或重新安装」），按钮是**「重新下载」而不是「启动」**

> 若版本列表里显示「启动」但点了报「尚未安装」，说明 `InstalledElsewhere` 判定没生效 —— 那是回归，而不是「数据丢了」。

**解决**：设置页切回原来的位置即可看到全部系统。

```bash
# 两个位置各有哪些系统
adb shell run-as com.li63050a.linuxandroid ls files/rootfs/
adb shell ls /storage/emulated/0/Android/data/com.li63050a.linuxandroid/files/rootfs/
```

### 2.9 明明选了外部存储，实际却装到了内部（`locationFallback`）

**原因**：外部位置**当前不可用**，`RootfsManager` 自动降级到内部并置 `locationFallback = true`。判定条件是「分区已挂载 + `getExternalFilesDir(null)` 非 null」，未挂载、或极少数不提供模拟外置分区的设备会命中。

**正常工作时的日志**（tag `RootfsManager`）：

```
W | RootfsManager | location unavailable: EXTERNAL
I | RootfsManager | location -> INTERNAL (/data/user/0/...)
```

```bash
adb logcat -s ProotTerm:V | grep "RootfsManager.*location"
```

UI 会显示「将安装到：<实际路径>」并给出降级提示 —— **不会静默降级**，看到实际路径不是外部就是降级了。开机自启时外部尚未挂载，`BootReceiver` 也会记一行 `boot: external storage not ready, ...`（见 §5.10）。

**解决**：等存储挂载好（重新插拔 SD / 重启后稍等）、确认系统设置里能看到该应用的外部目录，再重试。这是环境问题，不是应用问题。

### 2.10 旧位置残留 `.part` / `tmp_*` / `<id>.old` 文件

**原因**：这些是安装过程的中间产物，**跟着当时的存储位置走**。切换位置后它们留在旧位置，不会被自动清理（应用只清理**当前**位置下的）。

```bash
# 两个位置分别看一眼
adb shell run-as com.li63050a.linuxandroid ls -lR files/ | grep -E "\.part|tmp_|\.old"
adb shell ls -l /storage/emulated/0/Android/data/com.li63050a.linuxandroid/files/
```

**处理**：切回该位置后重新下载会续传/覆盖；确认不再需要时可直接删掉整个外部目录，或对目标系统点「卸载」。**不要手工删 `.installed` 以外的 rootfs 内容**——想重装就点卸载，想增量就重新下载。

### 2.11 把系统装到外部存储能撑过卸载应用吗

**不能。两个位置都是应用私有沙箱，卸载时都会被系统一并清除。**

- 内部 `/data/data/<pkg>`：卸载即删（系统行为）
- 外部 `/Android/data/<pkg>`：**同样是应用私有目录**，Android 11+ 卸载时同样被清除

所以「外部存储」解决的是**容量**，不是**持久性**。要跨卸载保留 rootfs，只能改用共享存储的「所有文件访问」或文档选择器（SAF），而这两者都违反硬性约束第 2 条（不申请存储权限、不使用 SAF），因此本轮不做 —— 这是有意识的取舍，见 [ARCHITECTURE.md](ARCHITECTURE.md) 的风险章。

**需要保留的场合**：先备份已装系统里的数据（`tar` 出来放到别处），再卸载应用。

---

## 3. 下载问题

### 3.1 一直失败 / 进度不动

**排查顺序**：

```bash
# 1) 网络与权限（应用只需 INTERNET）
adb shell dumpsys package com.li63050a.linuxandroid | grep -i permission

# 2) 看具体错误（会显示是哪个 URL、什么码）
adb logcat -s ProotTerm:V | grep -i "download\|HTTP\|url"
```

常见日志：

| 日志 | 含义 | 解决 |
| --- | --- | --- |
| `HTTP 403: <url>` | 源站拒绝（多为 UA/防盗链） | 换镜像；或检查 `RootfsDownloader` 的请求头 |
| `HTTP 404: <url>` | 清单里的 URL 失效 | 更新 `rootfs_manifest.json` 的 `url`/`mirrors` |
| `下载不完整 x/y: <url>` | 服务端提前断流 | 自动切下一镜像；全部失败则需换源 |
| `Content-Range 起点不匹配` | 服务端不支持/错误响应 Range | 删除 `.part` 重新下载 |
| `空响应体` | 拿到 200 但 body 为空 | 换镜像 |
| `SocketTimeoutException` | 连接 20s / 读写 60s 超时 | 网络问题；可换镜像重试 |

### 3.2 断点续传不生效，每次都从头开始

**原因**：`.part` 不在，或服务端不返回 `206`。

```bash
# .part 应该存在（下载中或取消后）
adb shell run-as com.li63050a.linuxandroid ls -l files/*.part
```

- 若 `.part` 不存在 → 检查是否被「SHA256 校验失败」路径删掉了（见 §3.4）
- 若服务端对 `Range` 请求返回 `200` 而非 `206` → 代码会**截断覆盖**从头写（这是设计好的降级），此时续传无效但结果正确
- 若返回 `416` → 视为已完整，会进入校验

### 3.3 「空间不足：需要约 N MB」

**原因**：`checkSpaceFor` 要求 `压缩包 + 压缩包×4 + 64MB`。

```bash
adb shell df -h /data
```

清理或换小体积版本（Alpine 最小）。注意这是**保守估算**（解压后通常不到 4 倍），不是精确值。

### 3.4 「SHA256 校验失败」

**原因**：下载内容损坏、被中间人篡改、或清单里的 `sha256` 写错了。

**注意副作用**：该路径会**主动删除 `.part`**（与续传策略相反，因为内容已确认损坏），所以校验失败后必须**从头下载**。

```bash
# 确认清单里的哈希
grep -A2 '"id": "alpine-3.20"' app/src/main/assets/rootfs_manifest.json
```

**解决**：换镜像重试；若持续失败，核对 `rootfs_manifest.json` 的 `sha256` 是否与上游一致。

### 3.5 Debian 三个版本没有完整性校验

**这是已知状态**：清单里 Debian 13/12/11 的 `"sha256": ""`，空串会**跳过校验**（只比对 HTTP `Content-Length`）。

相比 Alpine/Ubuntu 的强校验，这是保护降级。**要修复需从上游补齐哈希**，见 [COMPLIANCE.md §5.2](COMPLIANCE.md)。

### 3.6 取消下载后按钮卡在「取消中」

**这是本轮修复过的缺陷的回归信号。** 正常行为：点取消 → 按钮短暂显示「取消中…」→ 尽快恢复「下载」。

**原理**：`cancelInstall` 同时调 `downloader.cancel()`（结束活动 `Call`）与 `installJob.cancel()`（取消协程）；协程的 `finally` 会把残留的 `Cancelling` 状态兜回 `Idle`。

**若卡住**：说明 `finally` 兜底没执行到，或 `activeInstallId` 状态不一致。看日志：

```bash
adb logcat -s ProotTerm:V | grep -i "cancel"
```

期望看到 `Downloader: cancel requested`。

### 3.7 下载卡在「正在测速选择最快镜像 …」很久 / 一直不开始下载

**这不一定是故障，先看时间预算。** 自动优选（`MirrorProbeService`，§8.20）对**全部候选并发探测**，但有硬性超时：

| 参数 | 值 | 位置 |
| --- | --- | --- |
| 单镜像超时 | **4 秒**（连接/读/写各 4s） | `MirrorProbe.PER_MIRROR_TIMEOUT_MS` |
| 整体超时 | **6 秒** | `MirrorProbe.TOTAL_TIMEOUT_MS` |

所以**最坏情况 6 秒**就会出结果并进入下载阶段。若「正在测速」超过 ~7 秒仍未变，说明卡的不是测速本身，而是后续的 `HEAD`/连接建立。

```bash
adb logcat -s ProotTerm:V | grep -E "MirrorProbe|Install.*ranked"
# 正常会看到：
#   ranked: <url>=123ms, <url>=456ms
#   unreachable: <url>(<原因>)
# 整体超时会看到：
#   total timeout, falling back to manifest order
# 全部不可达会看到：
#   no reachable mirror, keep manifest order
```

**原因与处理**：

- **镜像多、网络差** → 6 秒后自动按清单顺序继续下载，**不需要用户干预**。测速失败**不会**阻断下载（这是硬要求：测速只是为了更快）。
- 想立刻开始下载 → 在**实例设置**里关掉「自动优选（并发测速，选最快的镜像）」开关，下载会直接用清单顺序（自定义源仍排在最前）。
- 探测方式先 `HEAD`，部分 CDN 不支持 HEAD 时会退化为 `Range: bytes=0-0` 的 GET，这会多花一轮往返，属预期。
- ⚠️ 测速结果只是**排序建议，不是信任来源**：源站可达 ≠ 内容正确，完整性仍只由 SHA256（清单非空时）保证。不要因为「测速显示很快」就跳过 §3.4 的校验。

### 3.8 下载总是失败在同一台镜像上 / 服务端返回 200 而不是 206

两种不同的根因，先分清是**换地址能好**还是**换地址也不好**。

```bash
adb logcat -s ProotTerm:V | grep -E "MultiPart|Downloader"
```

| 日志 | 含义 | 处理 |
| --- | --- | --- |
| `分块下载要求 206，实际 200（服务端不支持 Range）: <url>` | 该镜像**忽略了 `Range` 头**，把整个文件当 200 返回 | 这是**必须报错**的路径：照写会导致每块都含全量数据、合并后归档损坏。分块失败后会清理分块，并用**其余镜像**走单线程重试；若只剩这一个镜像则整体失败 → 换镜像，或把下载线程数设为 1 |
| `multipart failed on <url>, falling back to N other mirror(s)` | 首要地址分块失败，正在换地址重试 | 无需干预；若最终仍失败，说明所有候选都不可用 |
| `合并后长度不符 X/Y，已丢弃（请重试）` | 合并结果与 `Content-Length` 不一致，整份已删除 | 重试即可（不会解压损坏归档）；反复出现说明该镜像不稳定 |
| `分块不完整 X/Y (a-b)` | 单块提前断流，该块已删除 | 自动由上层进入重试 |
| `HTTP 404: <url>` | 清单里的地址失效 | 更新 `rootfs_manifest.json` |

**若某个镜像「单独用浏览器能下、应用里总失败」**：重点看是不是 200 而非 206（上表第一行）。服务端**不支持 Range** 时不会退回单线程 —— 分块失败不再对同一地址降级重下（慢网下整文件重下代价太大），只会换其他镜像；而单线程路径本身支持 Range 续传。若要强制走单线程，把实例设置里的**下载线程数设为 1**，此时 `wantThreads <= 1` 会直接走单线程路径，不会触发 206 校验。

### 3.9 `.part.N` 分块文件残留、占用空间

**症状**：`files/` 下出现 `<id>.<format>.part.0` … `.part.7`，以及可能的 `<id>.<format>.part.merge`。

**正常情况它们不该存在**——分块是下载过程中的中间产物，下列路径都会清理：

| 时机 | 清理动作 |
| --- | --- |
| 合并成功 | `mergeChunks()` 末尾 `cleanupChunks()` |
| 取消下载 | `MultiPartDownloader.cancel()` + 安装协程 `catch (CancellationException)` 里的 `cleanupChunksFor()` |
| 安装失败 | `catch (Exception)` 里的 `cleanupChunksFor()` |
| 降级单线程前 | `cleanupChunks()`（避免被误判为「已下完」） |
| **卸载系统** | `RootfsManager.uninstall()` → `deletePartFiles(id)`，**现在同时覆盖 `.part` 与 `.part.N`（含 `.src`）** |
| 切换存储位置前后 | 两个沙箱目录都会被扫（`allSandboxDirs()`） |

```bash
# 看两个位置分别有哪些残留
adb shell run-as com.li63050a.linuxandroid ls -l files/ | grep -E "\.part"
adb shell ls -l /sdcard/Android/data/com.li63050a.linuxandroid/files/ | grep -E "\.part"
```

**若确实残留**：

- 进程在下载中途被系统杀掉（非取消路径）→ 残留是可达的，重新下载时**完整的那一块会被复用**（日志 `chunk[a-b] reused`），不会浪费流量；但下次若走的是单线程路径，分块文件无法被复用。
- 确认不再需要时，**对目标系统点「卸载」**（会连带删 `.part` 与分块），或先切到对应存储位置再删；不要只手工删 `.installed`。
- ⚠️ **不要保留「来源不符」的分块**：分块是否可复用由**长度 + `.src` 来源标记**共同判定
  （`<id>.<format>.part.N.src`，内容是 `url|total`）。若你手工把某个分块文件改名/复制过来，
  它没有匹配的 `.src`（或 `.src` 指向另一个镜像），下次下载会打日志
  `chunk[a-b] discarded (source/length mismatch)` 并整块重下——这是**正确的保护**，
  不是「续传失效」。原因：镜像顺序由测速决定、本身不稳定，仅凭长度复用会把别的镜像的
  同长度块拼进来，产出「长度正确但内容损坏」的归档（Debian/Ubuntu 又没有 sha256 兜底）。
- 手工确认用：

```bash
# 分块长度应等于 (end - start + 1)，总长 = 各块之和
adb shell run-as com.li63050a.linuxandroid ls -l files/*.part.*
```

### 3.10 换源后 `apt update` 还是 404（arm64 走的是 ports 仓库）

**这是 arm64 上换源最常见的错误，也是本项目的重点防范对象。**

**原因**：Debian 的 **arm64 属于 `debian-ports`**，路径是 `.../debian-ports` 而**不是** `.../debian`；Ubuntu 的 arm64 在 **`ubuntu-ports`** 而**不是** `ubuntu`。指向错误的路径时，源本身语法没问题、文件也改成功了，但 `apt update` 会在 `dists/<codename>/Release` 上一路 404。

本项目的 `AptMirror` 内置镜像**已经全部使用 ports 路径**：

| 镜像 id | Debian 基址（arm64 正确写法） | Ubuntu 基址 |
| --- | --- | --- |
| `tuna` | `https://mirrors.tuna.tsinghua.edu.cn/debian-ports` | `https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports` |
| `ustc` | `https://mirrors.ustc.edu.cn/debian-ports` | `https://mirrors.ustc.edu.cn/ubuntu-ports` |
| `aliyun` | `https://mirrors.aliyun.com/debian-ports` | `https://mirrors.aliyun.com/ubuntu-ports` |
| `huawei` | `https://mirrors.huaweicloud.com/debian-ports` | `https://mirrors.huaweicloud.com/ubuntu-ports` |

```bash
# 看应用到底把源改成了什么（宿主机侧直接读 rootfs 内文件，不需要启动 guest）
adb shell run-as com.li63050a.linuxandroid cat files/rootfs/<id>/etc/apt/sources.list.d/debian.sources
# deb822：URIs: 行必须是 .../debian-ports（Debian）或 .../ubuntu-ports（Ubuntu）
# 旧式：cat files/rootfs/<id>/etc/apt/sources.list
adb logcat -s ProotTerm:V | grep AptSwitch
# 期望：deb822 rewritten x1 for <id> -> https://mirrors.<...>/debian-ports
```

**其他 404 的常见原因**（按可能性排序）：

1. **代号不对**。代号从 rootfs 内 `/etc/os-release` 的 `VERSION_CODENAME` 读取（**不硬编码猜测**，§8.21）。若该字段缺失，会退回字面量 `stable`（Debian）——`stable` 在多数国内镜像是**软链接**，可用但可能指向与系统不匹配的版本。核对：
   ```bash
   adb shell run-as com.li63050a.linuxandroid cat files/rootfs/<id>/etc/os-release
   # 需要 VERSION_CODENAME=bookworm / trixie / noble / jammy …
   ```
2. **改了源但没重启会话**。换源是直接改宿主机上的文本文件，guest 内的 apt 可能已缓存旧的源列表；且如果实例正在运行，应用会提示「该实例正在运行，换源后需重启会话才会生效」。**重启该实例的会话**再看。
3. **`Signed-By` 指向的密钥不存在**。换源会保留 `Signed-By:` 行不动（改错密钥路径会让 apt 拒绝整个源）。若报 `NO_PUBKEY` / `缺少签名`，按发行版文档补 `debian-archive-keyring` 等。
4. **走了 deb822 而不是你手工看的那个文件**。换源**优先改 deb822**（`/etc/apt/sources.list.d/*.sources`），只有完全没有 deb822 文件时才改旧式 `sources.list`。所以手工改 `sources.list` 而不动 deb822，是不会生效的。

**处理**：换一个镜像重试；仍不行则**恢复原始源**（§3.12）后用发行版自带方式排查。

### 3.11 「恢复原始源」提示「没有找到任何备份」

**这是诚实报错，不是 bug。** 文案为：

```
恢复失败：没有找到任何备份（.prootterm.bak），无法恢复。可能从未换过源，或 rootfs 是重新安装的。
```

**原因**：恢复只能从备份还原。备份文件是 `<原文件名>.prootterm.bak`，与源文件同目录：

| 发行版 | 备份路径 |
| --- | --- |
| Debian/Ubuntu（deb822） | `etc/apt/sources.list.d/<name>.sources.prootterm.bak` |
| Debian/Ubuntu（旧式） | `etc/apt/sources.list.prootterm.bak` |
| Alpine | `etc/apk/repositories.prootterm.bak` |

```bash
adb shell run-as com.li63050a.linuxandroid ls -l files/rootfs/<id>/etc/apt/sources.list.d/
adb shell run-as com.li63050a.linuxandroid ls -l files/rootfs/<id>/etc/apt/sources.list*
adb shell run-as com.li63050a.linuxandroid ls -l files/rootfs/<id>/etc/apk/repositories*
```

**什么时候会真的没有备份**：

- 从未换过源（此时也不需要恢复）
- **卸载后重新安装**了该系统 —— 新解压的 rootfs 里没有备份文件，属预期
- 换源写过但备份写入失败（日志 `AptSwitch: 备份失败: <name>`）。注意这种情况下**文件仍会被改写**，日志里同时会有 `deb822 rewritten` / `legacy rewritten`；此时只能手工改回，或从上游重新拉取该发行版的源文件。

⚠️ **不要用「换源成功」的文案反推有备份**：`AptSourceSwitcher.switchTo` 的返回值只说明「改写成功」，备份成功与否是另一条日志。两者都要看。

### 3.12 换源之后 apt 完全不能用（半个文件 / 语法错误）

**原因（最可能）**：写入过程被中断，留下了半个文件。写文件走的是「临时文件 + rename」的**原子**替换（§8.21），所以**正常情况下不会出现半个文件**——rename 是原子的，中断只会留下 `<name>.tmp`，原文件内容完好。

```bash
# 1) 先看有没有 .tmp 残留（有就说明写入了但没 rename 成功）
adb shell run-as com.li63050a.linuxandroid ls -l files/rootfs/<id>/etc/apt/
adb shell run-as com.li63050a.linuxandroid ls -l files/rootfs/<id>/etc/apt/sources.list.d/

# 2) 直接看当前内容对不对
adb shell run-as com.li63050a.linuxandroid cat files/rootfs/<id>/etc/apt/sources.list.d/<name>.sources
```

**处理顺序（从最省事到最彻底）**：

1. **恢复原始源**：实例设置 → 软件源 → 「恢复原始源」。有备份就能一键回到原状。
2. 有 `.tmp` 残留但目标文件正常 → 直接删掉 `.tmp` 即可，不影响 apt。
3. 目标文件确实被写坏且**没有备份** → 用同版本官方镜像的源文件内容手工覆盖。Debian/Ubuntu 至少要有：
   ```
   Types: deb
   URIs: http://deb.debian.org/debian
   Suites: <codename>
   Components: main contrib non-free
   Signed-By: /usr/share/keyrings/debian-archive-keyring.gpg
   ```
4. 彻底重来 → 卸载该系统后重新下载安装（rootfs 全新，源文件回到发行版默认）。

> 换源**只改文本**，不会动签名密钥、也不会执行任何 guest 命令，所以「apt 坏了」几乎总能通过还原文件修好，不需要重装系统。

---

## 4. 解压问题

### 4.1 `解压条目失败: <name>`

**原因**：单个条目写入失败（权限、磁盘满、非法路径）。

```bash
adb shell df -h /data
```

**注意**：解压采用「目录先保持可写、最后逆序 chmod 还原」的策略。若中途失败，`tmp_<id>` 会残留（下次安装开头会 `cleanupTemp` 清掉）。

### 4.2 `不支持的压缩格式: <format>`

**原因**：清单里的 `format` 不是 `tar.gz` / `tar.xz`。

> 本轮已加**前置校验**：`ManifestLoader` 会在解析阶段就拒绝未知 `format`，给出明确提示，而不是等到解压才报错。若你看到的是解析期错误（`清单解析失败: 版本 xxx 的 format=... 不受支持`），说明校验正常工作。

**解决**：改清单为受支持格式，或在 `RootfsExtractor.openDecompressor` 增加分支**并同步**更新 `ManifestLoader` 的支持集合。

### 4.3 符号链接变成了普通文件 / 权限丢了

**这是严重问题**（硬性约束第 7 条）。

```bash
adb shell run-as com.li63050a.linuxandroid ls -l files/rootfs/alpine-3.20/bin/sh
# 必须是 lrwxrwxrwx ... -> /bin/busybox
```

**可能原因**：

- 用了 `java.nio.file.Files` 相关 API（某些实现会跟随链接）
- 删除已存在路径时用了会跟随链接的方法 —— 正确做法是 `Os.lstat` + `Os.remove`

> ⚠️ **禁止**改回 `java.nio.file`：它还是 API 26+，minSdk 24 上会直接崩。

### 4.4 `解压结果为空`

**原因**：`tmp_<id>` 是空目录，说明解压实际没产出内容（可能是下载文件损坏或格式判断错误）。

**解决**：删掉 `.part` 与 `tmp_<id>` 重新下载。

### 4.5 升级安装失败，旧版本还在吗

**本轮改进点**：`finalizeInstall` 现在是**可回滚**的。

流程：旧目录先改名为 `rootfs/<id>.old` → 新目录 rename 到位 → 成功后才删 `.old`。若新目录 rename 失败，会把 `.old` **挪回原位**，旧安装不丢。

```bash
# 若看到这个目录，说明发生过回滚失败（极小概率）
adb shell run-as com.li63050a.linuxandroid ls files/rootfs/
# rootfs/alpine-3.20.old 存在 -> 手工改回：mv .old 到正式名
```

日志里会有一行明确的 error 说明备份路径。

---

## 5. 终端与 UI 问题

### 5.1 点 CTRL 后没有字母键可点

**这是本轮修复过的缺陷的回归信号。** 正常行为：点 CTRL → 按钮半透明 → **下方展开一行 `A B C … Z`**。

**历史原因**（供理解，勿复现）：旧代码用 `resources.getIdentifier("btn_key_$ch", ...)` 查找 `a`–`z`，但布局里**从来没有**这些 id，循环恒返回 0、静默空转。

**若回归**：检查 `MainActivity.buildLetterRow()` 是否被 `setupHotkeys()` 调用，以及 `item_ctrl_key.xml` 是否存在。

### 5.2 CTRL 字母键没反应 / 界面还是没变化

先确认 shell 在运行（未运行时会提示「请先启动 shell」）。

字母键只在**交互式** shell 中有意义。非交互 `sh` 下 `Ctrl+C` 等不会表现出可观察的行为差异。建议先 `sh -i`。

### 5.3 快捷键（ESC/TAB/方向键/CTRL/ALT）点了像没反应，或直接打出了 `^[[A` 这类字面文本

**先分清是「没发出去」还是「发出去了但对方不认」。** 因为本项目**没有 PTY**（见 §2.5），shell 处于非交互模式：不打印提示符、不回显按键，`方向键` 也不会让任何东西动起来——**「没反应」在很大程度上是预期行为**。按键真正生效的场景是 `sh -i`、`vi`/`vim`、`readline` 程序（`bash` 的 `sh -i`、`cat` 读 stdin 等）。

**快捷键必须发出的是真实控制序列**（硬性约束第 16 条）：

| 按键 | 发出的字节 | Kotlin 字面量 |
| --- | --- | --- |
| ESC | `0x1B` | `"\u001b"` |
| TAB | `0x09` | `"\t"` |
| 回车 | `0x0A` | `"\n"` |
| ↑ / ↓ / → / ← | `ESC [ A` / `B` / `C` / `D` | `"\u001b[A"` … |
| HOME / END | `ESC [ H` / `ESC [ F` | `"\u001b[H"` / `"\u001b[F"` |
| PGUP / PGDN | `ESC [ 5 ~` / `ESC [ 6 ~` | `"\u001b[5~"` / `"\u001b[6~"` |
| CTRL + `a`–`z` | `0x01`–`0x1A` | `((ch.uppercaseChar().code - 'A'.code) + 1).toChar()` |
| ALT + 字母 | `ESC` + 字母 | `KEY_ESC + ch` |

**在 guest 里确认到底收到了什么**（这是唯一可靠的验证方式，别靠肉眼猜）：

```bash
sh -i
# 进入交互式 shell 后按键，或用下面的命令看字节
cat -v          # 按方向键 -> 应显示 ^[[A ^[[B ^[[C ^[[D
od -c           # 更精确：ESC 显示为 033，TAB 为 \t，回车为 \n
```

若 `cat -v` 里出现的是**字面的 `^[[A` 四个字符（即 `^`、`[`、`[`、`A`）而不是 `ESC [ A` 三字节，说明发的是字符串而非控制字符。

**常见误报（不是 bug）**：

- 在**非交互** `sh` 里按方向键、TAB、CTRL+C 本来就没有任何可见效果 —— 没有行编辑器、没有历史、没有 TAB 补全。**用户常把这一条报成「按键坏了」**，实际先 `sh -i` 或进入 `vi` 再试。
- CTRL 字母键先要在终端页点一下 `CTRL`（按钮变半透明、下方展开 `A B C … Z`）再去字母行点字母；直接点字母行不会带上修饰键。

**若确实回归**（按了键但没有字节写入进程）：检查 `MainActivity.setupHotkeys()` 里各 `btn_key_*` 是否绑定到 `sendRawKey`，以及常量表 `KEY_ESC`/`KEY_UP`…`KEY_PGDN` 是否仍是上表的控制序列。发出内容不写 `AppLogger`（只有 `Proot/send:` 一行 DEBUG 会记录命令本身，见 §7.2）。

### 5.4 输入框弹出了系统「安全键盘」/ 密码键盘（有些国产 ROM 还会盖住半个屏幕）

**原因**：命令输入框的 style 被人改动了。正常用 `App.TerminalInput`：

```xml
<!-- res/values/themes.xml -->
<style name="App.TerminalInput" parent="Widget.AppCompat.EditText">
    <item name="android:inputType">textVisiblePassword|textNoSuggestions|textMultiLine</item>
    <item name="android:imeOptions">actionNone|flagNoExtractUi|flagNoFullscreen</item>
    ...
</style>
```

```xml
<!-- res/layout/view_terminal.xml -->
<EditText android:id="@+id/edit_command"
          style="@style/App.TerminalInput"
          android:importantForAutofill="no" />
```

`textVisiblePassword` 让输入法**认为这是可见密码框**，从而不启用密码安全键盘、也不做联想/自动纠正——终端命令里的 `|`、`/`、`$`、`-` 不会被改掉。**硬性约束第 15 条**：禁止给这个输入框使用 `textPassword` / `textWebPassword`。

```bash
# 静态自检：这两条必须为空
grep -n "textPassword\|textWebPassword" app/src/main/res/layout/view_terminal.xml
grep -rn "textPassword\|textWebPassword" app/src/main/res/
```

**解决**：让 `edit_command` 恢复 `style="@style/App.TerminalInput"`，且不要在任何子 View 上覆盖 `android:inputType`。

### 5.5 输入法上的「回车」不发送命令（只是换行/没反应）

**原因**：`imeOptions` 里混进了**动作型**取值（`actionSend` / `actionSearch` / `actionGo` / `actionDone`）。一旦设了动作型，回车键会变成「发送/搜索」图标，`EditText` 收到的是 `IME_ACTION_*` 而不是 `KEYCODE_ENTER`，终端就拿不到换行。

**正常行为**：`App.TerminalInput` 用 `actionNone`，输入法显示普通回车，物理键与软键盘回车都以 `\n` 结尾交给 `ProotSession.write()`；发送按钮是独立的 `btn_send`。

```bash
grep -rn "actionSend\|actionSearch\|actionGo\|actionDone" app/src/main/res/
```

有输出即为回归根因。

### 5.6 输入法变成全屏编辑 / 抽出了单独的编辑框，看不到终端

**原因**：`imeOptions` 少了 `flagNoExtractUi` / `flagNoFullscreen`，横屏或小屏时系统会把输入框「抽出」成全屏编辑界面（Extract UI）。这两个 flag 就在 `App.TerminalInput` 里，同样属于「style 被改动」的症状。

### 5.7 终端输出突然少了一半

**不是 bug，是设计**：输出缓冲上限 `MAX_OUTPUT_CHARS = 200_000`，超出后**丢头部一半**（摊还成本，避免每次追加都截断）。

**完整历史仍在日志里**：抽屉 → 日志，或 `files/logs/app.log`。

### 5.8 搜索过滤后内容看起来不对

搜索是**逐行**过滤（`contains`，忽略大小写），不是高亮。清空搜索框即恢复全文。

### 5.9 退出应用（切后台 / 划掉最近任务）后 shell 就没了

**这不应发生。** 会话由前台服务托管：shell 的所有者是 `SessionManager`（进程内**多实例注册表** `Map<String, SessionSlot>`）+ `ProotService`（前台服务），`MainActivity` 只是观察者，`onDestroy` **不会**结束会话（硬性约束 §8.13），服务也声明了 `android:stopWithTask="false"`（划掉最近任务不会停服务）。

**若真的丢了**，按顺序排查：

```bash
# 1) 会话与服务是否还在
adb shell run-as com.li63050a.linuxandroid ps -A | grep proot
adb shell dumpsys activity services com.li63050a.linuxandroid | grep -i prootservice

# 2) 服务是否被系统以「前台服务被回收」为由销毁（属于 §5.11 的场景）
adb logcat -s ProotTerm:V | grep -E "ProotService.*onDestroy|session ended"
```

- 通知（`PRoot 会话`）是否还在？在就说明服务健在，问题在别处
- **国产 ROM 的「休眠/深度睡眠/后台清理」**会连前台服务一起杀。到系统设置里给本应用关闭省电限制、允许后台运行（见 §5.10）
- 会话是被**用户主动结束**的：`adb logcat` 里会有 `ProotService: stop requested`
- 若日志显示 `auto started` / `started` 后立刻 `exit ... code=`，那不是「丢会话」而是子进程自己退了（guest 里 shell 退出，例如 `exit`）

### 5.10 开机后没有自动启动 shell

**先确认自启动真的开了**（设置页的两个条件缺一不可）：

- 「开机自启动」开关已打开
- **已选定自启目标系统**（`AppPrefs.autoStartId` 非空）；只开开关不选系统会被直接跳过

```bash
# 看广播是否到达（这是第一步，能到就说明系统层面没问题）
adb logcat -s ProotTerm:V | grep
# 关键词（tag=BootReceiver）：
#   boot: auto start disabled, skip          -> 开关没开
#   boot: auto start enabled but no target, skip -> 没选目标系统
#   boot: auto start <id> (action=...)       -> 广播到了，已交给服务
#   boot: service launch refused; ...        -> 服务启动被系统拒绝
```

广播没到（**一条 `BootReceiver` 日志都没有**）时，按以下顺序看，全部是**平台限制，不是代码 bug**：

| 原因 | 怎么确认 | 解决 |
| --- | --- | --- |
| 用户用过「强制停止」 | 应用信息页显示「已停止」/ 灰掉的「打开」按钮 | **系统限制**：被强制停止的包在用户**再次手动打开应用之前**收不到 `BOOT_COMPLETED`。打开一次应用即可恢复，无法绕过 |
| 国产 ROM 拦截自启动（MIUI / EMUI / ColorOS / 一加 / vivo） | 系统设置里有「自启动」「后台运行」白名单 | 在系统管家/电池设置里显式允许本应用**自启动**与**后台运行**；部分 ROM 还需允许「锁屏后继续运行」 |
| 电池优化 / 应用休眠 | `adb shell dumpsys deviceidle whitelist \| grep linuxandroid` | 设置里把本应用加入「不优化/无限制」白名单 |
| 未授予 `RECEIVE_BOOT_COMPLETED` | `adb shell dumpsys package com.li63050a.linuxandroid \| grep -i boot` | 正常安装即自动授予（普通权限，无需运行时申请）；缺失说明 Manifest 被改 |
| 广播被禁 | `adb shell cmd appops get com.li63050a.linuxandroid` | 检查是否有异常限制项 |

**广播到了但系统没起来**（有 `boot: auto start ...` 但没有 `auto started ...`）：

校验刻意放在服务里而不是 `BootReceiver` 里（广播只有 10 秒预算，且开机瞬间外部存储可能还没挂载）。看 `tag=ProotService` 的日志：

| 日志 | 含义 | 解决 |
| --- | --- | --- |
| `version <id> not in manifest, abort` | 清单里没有这个版本（清单更新后 id 变了） | 设置页重新选一次自启目标 |
| `version <id> not installed, abort` | 目标系统**已被卸载**，或装在**另一个存储位置** | 在「我的系统」页装回来；若装在另一位置，切回该位置或用「重新下载」（见 §2.8） |
| `manifest load failed for auto start` | 清单解析失败（见第 6 节） | 按 §6 排查 |
| `start failed: ...` | proot 启动失败，多为 §2.7 的外部存储 noexec | 按 §2.7 处理 |

后三种情况服务会通过 `SessionManager.notifyStartFailed(id, ...)` 给出终态回调（文案形如「%s 尚未安装，无法开机自启动」或「清单中找不到版本 %s」），**重开应用后在终端页/状态栏即可看到失败原因**。

> 为什么不处理 `LOCKED_BOOT_COMPLETED`：那是「直接启动」（Direct Boot）阶段，用户还没解锁，应用私有目录仍处于**凭据加密**状态，读 `SharedPreferences` 会拿到错误结果。因此 `BootReceiver` 只注册 `BOOT_COMPLETED` 与 `MY_PACKAGE_REPLACED`，这是有意设计，不是遗漏。

### 5.11 正在跑会话，但通知栏没有「PRoot 会话」常驻通知

**会话其实还在跑**（`adb shell ps -A | grep proot` 有输出），只是通知被系统藏掉了。

**原因（Android 13+ 最常见）**：用户拒绝了 `POST_NOTIFICATIONS`。**拒绝只影响通知可见性，不影响服务运行**——前台服务照常跑，shell 照常执行。

```bash
# 确认权限状态
adb shell dumpsys package com.li63050a.linuxandroid | grep -i POST_NOTIFICATIONS
# granted=false 就是它

# 重新授予（调试；需 usb 调试）
adb shell pm grant com.li63050a.linuxandroid android.permission.POST_NOTIFICATIONS
```

用户侧路径：**系统设置 → 应用 → ProotTerm → 通知 → 允许**（部分 ROM 在「通知管理」里，注意别只开了「静默通知」）。

其它可能：**该通知渠道被单独关闭或调成静音**。渠道是 `proot_session`，重要性 `IMPORTANCE_LOW`（设计如此：常驻通知不该响铃/震动），渠道一旦被用户关闭，应用**无法**再自行打开。

```bash
# 渠道状态（API 26+）
adb shell dumpsys notification_manager | grep -A5 proot_session
```

系统设置 → 应用 → ProotTerm → 通知 → 「PRoot 会话」渠道 → 打开。

> 应用启动时会请求一次通知权限（`MainActivity.requestNotificationPermissionIfNeeded()`）；被拒绝后不会再反复弹窗，需手动去设置里开。

### 5.12 通知消失了，但 shell 还在跑（无法从通知栏结束会话）

**这是已知限制，本轮如实记录为取舍，不是 bug。**

**成因**：系统回收了前台服务（内存紧张 / OEM 后台清理）→ `ProotService.onDestroy()` 执行。但按硬性约束 §8.13，`onDestroy` **故意不调用** `SessionManager.stop()` / `stopAll()`——`onDestroy` 不等于「用户要结束会话」，在这里杀会话会让「后台保持运行」和「开机自启动」双双失效。于是会话进程活着，托管它的服务却没了。

服务被系统以空 Intent 重建时会走 `onStartCommand` 的 `else` 分支直接 `stopSelfSafely()`（没有 action 说明无事可做），**因此目前没有「不重开应用就重新贴出通知」的路径**。

**如何结束这个 shell**：**重新打开应用 → 终端页 → 结束会话**（或在「我的系统」页对运行中的系统点结束）。重开后界面会通过 `SessionManager` 观察到仍在运行的会话（`runningIds()`），用它的结束入口即可正常终止。**多实例下每个实例各自独立**，「结束」只作用于当前焦点实例，见 §5.16。

**如何确认是这个场景**：

```bash
adb logcat -s ProotTerm:V | grep -E "ProotService.*onDestroy|session ended|stop requested"
# 有 "onDestroy" 但紧接着没有 "stop requested" -> 服务被系统回收，会话被有意保留
```

**规避**：给应用关闭电池优化、允许后台运行（减轻被回收的概率），见 §5.10 的同一条设置。

### 5.13 guest 里的 IP 和手机一样

**这不是 bug，也不需要任何代码参与。**

PRoot 是 **ptrace 层的路径重定向**，不是虚拟化/容器：guest 与手机**共用同一个 Linux 内核、同一套网络栈和同一张网卡**。所以 guest 里看到的就是手机自己的 IP，没有独立的容器 IP，也不存在 NAT —— 流量本来就是手机直接发的。

```bash
# guest 内
ip addr          # 一般只有 lo + 手机的实际接口（wlan0 / rmnet_data0 …）
hostname -I      # IP 通常是 192.168.x.x（Wi-Fi）或运营商地址
cat /etc/resolv.conf   # DNS 也是宿主的
```

```bash
# 手机上（对比，应当一致）
adb shell ip addr
adb shell dumpsys wifi | grep -i "IP address"
```

**「guest 里没有网络」怎么查**（顺序是从外到内，别一上来怀疑应用）：

1. 手机本身能上网吗？（关掉 Wi-Fi 用流量、或反过来试）
2. `ping -c1 1.1.1.1` 通但域名不通 → **DNS** 问题，不是网络问题；看 guest 的 `/etc/resolv.conf`
3. 手机开着 VPN / 代理 / 分应用代理时，guest 的流量可能不走它 → 用手机浏览器访问同一地址对比
4. 应用只申请了 `INTERNET` 一个网络相关权限（外加开机自启与前台服务权限），**没有任何按应用限网的自身设置**；但系统级的「流量节省」「后台数据限制」「仅 Wi-Fi」会同样作用于本应用：
   ```bash
   adb shell dumpsys package com.li63050a.linuxandroid | grep -i permission
   # 应只看到 INTERNET / RECEIVE_BOOT_COMPLETED / FOREGROUND_SERVICE* / POST_NOTIFICATIONS
   ```
5. 企业/校园网需要认证时，认证是在宿主侧完成的，guest 直接复用即可，不用在 guest 里再登一次。

### 5.14 旋转屏幕后终端输出丢了

**不应发生** —— 已用 `configChanges` 兜住，Activity 不重建。若真的丢了，检查 `AndroidManifest.xml` 中 Activity 的 `android:configChanges` 是否被改动。

### 5.15 只有「一个」实例在跑 / 启动第二个实例时第一个被杀掉

**这不应当发生。** 多实例并存是 §8.17 的硬性约束：`SessionManager` 用 `Map<String, SessionSlot>` 为每个实例维护**独立**的进程、输出缓冲与代次计数器（每个槽有自己的 `AtomicInteger epoch`），`MainActivity.startSession()` 里**没有**任何「先结束别人」的调用。

旧单例时代的**进程内会话单例类已删除**（其日志 tag 也随之消失）；如果你在代码里还看到那个类名，说明改动被回退到了旧实现。

```bash
# 1) 两个实例是否真的同时在跑（每个实例一个 proot 进程）
adb shell ps -A | grep -i proot

# 2) 会话注册表里有哪些实例在跑（日志每次状态变化都会打 id）
adb logcat -s ProotTerm:V | grep -E "SessionManager|MainActivity.*alongside"
# 期望看到：
#   MainActivity: starting <id2> alongside <id1>
#   SessionManager: started <id2> @ <location> limits=…
# 且 <id1> 没有任何 exit 行

# 3) 启动第二个实例时是否误发了「结束全部」
adb logcat -s ProotTerm:V | grep -E "stop requested|stopAll"
# 不应在「启动第二个」的时刻出现 stop requested (all)
```

**按下列顺序找根因**：

| 检查点 | 正确实现 | 出错意味着 |
| --- | --- | --- |
| `MainActivity.startSession(id, name)` | 只切 `focusedId`，**不**触碰其他实例 | 被人加回了「启动前先结束其他会话」 |
| `ProotService.startSession()` | 只判断 `SessionManager.isRunning(id)`（目标实例） | 用了全局「有任意会话在跑就跳过/先停」 |
| 启动任务跟踪 | `startJobs: ConcurrentHashMap<String, Job>` 按 id 存 | 退回单个 `startJob` 字段 → 并发启动时后者 cancel 前者 |
| 代次校验 | 每个槽自己的 `slot.epoch` | 退回全局 epoch → 两个实例同时启动会互判「已取消」而误杀 |
| `releaseIfIdle()` | 只在 `!hasAnyRunning()` 时收服务 | 无条件 `stopSelf()` → 一个实例失败会连累其他实例的通知与服务 |

⚠️ **不要用「同时只有一个」的旧说法解释这个现象**：旧文档曾写「同时仅一个运行会话」，那是单例时代的描述，已被 §8.17 取代。现在同时只允许一个的只有**同一个 id**（见 §5.17）。

### 5.16 终端里出现了另一个实例的输出

**原因**：`SessionManager.Observer` 的**所有回调都带实例 `id`**（`onOutput(id, text)` / `onSessionState(id, …)` / `onExit(id, …)`），观察者必须按 `focusedId` 过滤。`MainActivity.onOutput` 的第一行就是 `if (id != focusedId) return`。

```bash
# 确认实例 id 与输出归属（tag SessionManager / Proot）
adb logcat -s ProotTerm:V | grep -E "SessionManager.*started|Proot.*send:"
```

**若串台，检查这几处**：

- `MainActivity.onOutput` 是否还有 `id != focusedId` 的提前返回（**这是第一道闸**）
- `onSessionState` / `onExit` 是否也按 `focusedId` 过滤（否则顶栏状态与「展开另一个实例的失败原因」会串）
- `renderSessionOutput()` 用的是 `SessionManager.snapshot(focusedId)`，而不是某个全局 `session` 字段
- 发送命令走 `SessionManager.sendCommand(focusedId, cmd)` / `sendRaw(focusedId, data)`，不是无参的全局写入

**注意：切焦点≠切进程。** `focusedId` 表示「终端显示哪一个实例」，**不表示所有权**。切换焦点（在「我的系统」页点另一个实例的「打开终端」）**绝不能**启动或结束任何进程。其他实例的输出不会丢——它们留在各自的会话缓冲里，切过去时由 `renderSessionOutput()` 从快照重放。

```bash
# 切焦点前后对比：进程集合应当完全不变
adb shell ps -A | grep -i proot        # 切之前
# （在应用里切换终端焦点）
adb shell ps -A | grep -i proot        # 必须一模一样
```

### 5.17 同一实例启动了两次 / 点「启动」没反应

**同一个实例同时只允许一个运行中的 shell**（§8.13）。这与「多实例并存」不矛盾：并存的单位是**不同的 id**。

**正常行为**：该实例已在跑时，`launchMyApp()` / `launchDistro()` 会直接跳到终端页并 `renderSessionOutput()`，**不重启进程**（日志里不会有第二条 `started <id>`）。

**若真的重复启动了同一个 id**，看日志：

```bash
adb logcat -s ProotTerm:V | grep -E "already running, skip|prepare\(<id>\)"
# 期望最多一条 started <id>；重复点击应命中：
#   ProotService: instance <id> already running, skip
```

**若同一 id 起了两个 proot 进程**，这是真缺陷——按 §8.13 的「重复启动必须先结束该实例的旧会话」处理：`SessionManager.prepare(id, name)` 会先 `destroy()` 该槽自己的旧会话再覆盖 `slot.session`；少了这一步就会留下无人持有、UI 也无法结束的孤儿进程。

### 5.18 `instances.json` 损坏 / 丢失，实例设置被静默重置

**症状**：某实例的资源限制、自定义镜像、下载线程数、换源记录全部回到默认值（限制变「不限制」、镜像变空、线程数回到 4、软件源显示「当前使用发行版原始源」）。

**先确认日志**：

```bash
adb logcat -s ProotTerm:V | grep -E "InstanceStore|instances.json"
# 解析失败：E | InstanceStore | 读取 instances.json 失败，回退为空配置
# 写入失败：E | InstanceStore | 写入 instances.json 失败（rename 未成功）
# 清理：    I | InstanceStore | pruned N stale config(s): [...]
```

```bash
# 文件本身是否存在、是否可解析
adb shell run-as com.li63050a.linuxandroid ls -l files/instances.json
adb shell run-as com.li63050a.linuxandroid cat files/instances.json
```

**三类原因，处理方式不同**：

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| 日志有「读取失败，回退为空配置」 | JSON 损坏（手工编辑写坏、或文件系统问题） | **配置需重设**（每个实例重新填一次实例设置）。损坏不会让应用崩溃，也不会影响 rootfs 本体与运行中的会话 |
| 文件不存在，且从没配过 | 正常：没有记录时 `get(id)` 返回默认配置，**不落盘** | 无需处理；首次保存后才会生成文件 |
| 文件在，但某实例的配置没了 | 该实例被 `remove(id)` 或 `pruneMissing()` 清过（卸载、或扫描时发现 rootfs 目录已不存在） | 属预期；重新安装后重设即可 |

**设计要点（排查时别误判）**：

- `instances.json` 固定在**内部** `filesDir`，**不随存储位置切换**。所以「切到外部位置后配置还在」是正常的，不要把它当成配置串位。
- 写入走「临时文件 `.tmp` + rename」的原子替换，正常中断只会留下 `instances.json.tmp`，原文件内容完好。
- 某个实例配置读不出来**不会**影响其他实例——每个 id 各自一条记录。
- 换源配置（`apt_mirror`）与**文件是否真的换过**是两回事：`instances.json` 只记录「应用认为换到了哪个源」。实际依据永远看 rootfs 内的源文件与 `.prootterm.bak`（§3.11）。

### 5.19 设了资源限制后 shell 起不来 / 一启动就退出

**原因**：ulimit 的取值把 guest 卡死了。最常见的是**虚拟内存（`-v`）设得太低**。

`-v` 限制的是**地址空间**（不是物理内存）。动态链接器在地址空间被压到过低时会直接加载失败——代码注释给出的**有效下限约 64MB**，UI 提示文案也写明了这一点。

```bash
# 1) 看实际下发的 ulimit 包装脚本（tag Proot，启动时必打）
adb logcat -s ProotTerm:V | grep "limits"
# 形如：
#   limits <id>: 内存 512MB · 进程 256 · FD 1024 -> sh -c 'ulimit -v 524288 -u 256 -n 1024; exec "/bin/sh"'
```

⚠️ **另一类「设了限制就起不来」的根因：argv 传错**。脚本必须作为**独立参数**传给
`/bin/sh -c`（`toGuestCommand()` 返回 `["/bin/sh", "-c", 脚本]` 共 3 个元素）。
proot 不是 `env`/`nice`，它把最后一个参数当**可执行文件路径**解析；若把整段脚本拼成
**一个**字符串，proot 会去找名为 `ulimit -v …; exec …` 的文件：

```
'ulimit -v 524288; exec "/bin/sh"' not found (root = …, $PATH=…)
```

症状是**凡是设了资源限制的会话一律启动失败**（不设限制的正常）。若看到这条错误，
检查 `toGuestCommand()` 是否退回了单字符串实现。

逐项核对**单位换算**（这是最容易写错的地方）：

| 配置项 | ulimit 选项 | 单位 | 换算 |
| --- | --- | --- | --- |
| 虚拟内存 MB | `-v` | **KB** | MB × 1024（512MB → `-v 524288`） |
| 栈 MB | `-s` | **KB** | MB × 1024 |
| 最大进程/线程 | `-u` | 个 | 原值 |
| 最大文件描述符 | `-n` | 个 | 原值 |
| CPU 时间 | `-t` | **秒** | 原值 |

**处理**：

- `-v` 设得比 64MB 还小（例如 64 或更小）→ 调大，或直接用**预设**（实例设置 → 预设：不限制 / 轻量 512MB / 标准 1GB / 编译用 2GB）。
- **数值为 0 表示不限制**，此时该项**不会生成任何 `ulimit` 语句**。若日志里某选项没出现，先确认是不是填了 0。
- 全部为 0 → `toScript()` 返回 `null`、`toGuestCommand()` 返回单元素 `["<shell>"]`，proot 命令行就是裸 shell 路径，**没有**任何 ulimit 包装（这是正确行为，不是漏下发）。
- 注意 `-t` 限制的是 **CPU 累计秒数**，不是 CPU 核数、也不是限速。跑编译这类长任务时设得太小会被内核直接 SIGKILL，表现为「跑一会儿就被杀了」——那是限制生效，不是崩溃。UI 文案已如实说明这一点。
- 若日志里 `limits` 一行的值与界面填的不一致 → 检查是不是**改完没重启该实例的会话**（§5.20）。

### 5.20 改了资源限制但没有任何变化

**这是设计如此：资源限制只影响新建会话。**

ulimit 是**进程级**属性，只能在 fork 时下发；已经跑起来的 shell 无法事后追加限制。因此：

- 实例设置里的说明就是「仅对新启动的会话生效，修改后需重启该实例」；
- 保存后的 Toast 是 `limits_saved`：「资源限制已保存，重启该实例后生效」。

```bash
# 确认会话用的是哪套限制：看最近一次启动打出的 limits 行
adb logcat -s ProotTerm:V | grep "limits <id>"
```

**正确操作**：

1. 实例设置里改好 → 保存；
2. **结束该实例的会话**（终端页返回键 → 结束会话 / 通知栏「结束」）；
3. 重新启动该实例，再看 `limits <id>` 日志核对。

⚠️ 两点容易误判：

- 结束时若选了「后台保持运行」，会话**并没有**重启，限制自然没变。
- 「结束」在多实例下**只作用于当前焦点实例**，其他实例不受影响，各自的限制也各归各的。

### 5.21 停止会话后 rootfs 仍被占用 / 残留孤儿 shell 进程

**症状**：点了结束会话，但 `ps -A | grep proot` 仍有进程；卸载该实例时提示目录删不掉、或统计出来的占用不下降。

**根因（第一顺位）：ulimit 包装少了 `exec`。**

资源限制的落地方式是 `sh -c 'ulimit …; exec <shell>'`（§8.18）。**`exec` 不可省略**：没有它进程树是

```
proot → sh -c（包装层） → sh（真正的 shell）
```

`ProotSession.destroy()` 只能杀掉它直接持有的那一层（包装用的 `sh -c`），**真正的 shell 会变成孤儿**继续持有 rootfs。

```bash
# 1) 确认会话是否真的结束了
adb shell ps -A | grep -i proot

# 2) 看启动命令里有没有 exec（tag Proot 的 limits 行）
adb logcat -s ProotTerm:V | grep -E "limits|start .*cmd="
# 正确形如：ulimit -v 524288 -u 256; exec "/bin/sh"
# 缺 exec 形如：ulimit -v 524288 -u 256; "/bin/sh"   ← 这就是根因

# 3) 结束时的回收情况
adb logcat -s ProotTerm:V | grep -E "exit id=… code=|SessionManager"
```

**按可能性排序的处理**：

1. **缺少 `exec`**（`ResourceLimits.toScript` 被改动）→ 恢复 `exec`；这是硬性约束，不是可选项。
2. 无限制实例（`toScript()` 返回 `null`）**不存在**这层包装，进程树本来就是 `proot → shell`，此时仍残留就按第 3 点查。
3. 正常路径下的 `destroy()` 异步回收进程，**状态会立刻置为 IDLE**（UI 不等内核收尸）。若此刻 `ps` 还能看到进程，稍等再查一次。
4. API 24/25 上 `destroy()` 用的是**无超时**的 `p.waitFor()`，理论上可能长时间阻塞 reaper 线程（不阻塞 UI）——这是已知的平台硬限制。
5. 卸载时应用会先结束该实例的会话（`ProotService.stopIntent(context, id)`），**只结束这一个**，其他实例继续跑。

**强制清理**（确认进程确实不再需要时）：

```bash
adb shell run-as com.li63050a.linuxandroid kill <pid>      # 需要 debug 包
# 或直接从通知栏「结束」/ 应用内结束会话走正常路径
```

⚠️ **不要**用「卸载应用」当清理手段——那会把所有 rootfs 一起删掉。要清单个实例就用应用内的「卸载」。

### 5.22 自定义镜像源保存不了 / 提示「地址必须以 http:// 或 https:// 开头」

**这是有意校验，不是 bug**（§8.20）。实例设置里的自定义镜像源**只接受 http/https**。

```bash
adb logcat -s ProotTerm:V | grep -E "mirror_custom_invalid|saved limits"
# 保存成功会打：instance <id> saved limits=… mirror='<规范化后的地址>' …
```

**会被拒绝的输入**（保存时 Toast `mirror_custom_invalid`，且**配置不被写入**）：

| 输入 | 结果 | 原因 |
| --- | --- | --- |
| `file:///sdcard/rootfs.tar.gz` | ❌ 拒绝 | 非 http/https；让 OkHttp 加载本地文件会绕过「只读写应用私有目录」的边界 |
| `ftp://example.com/…`、`content://…` | ❌ 拒绝 | 同上 |
| `/data/xxx`（裸路径） | ❌ 拒绝 | 没有 scheme |
| `HTTP://EXAMPLE.COM/MIRROR/` | ✅ 通过 | 校验前先 `lowercase()`，**大小写不敏感** |
| `https://example.com/mirror/` | ✅ 通过，存为 `https://example.com/mirror` | 自动去掉尾部 `/`，避免拼接时出现 `//` |
| 留空 | ✅ 通过 | 空 = 不自定义，`mirror_override` 存空串 |

```bash
# 静态确认校验与规范化逻辑
grep -n "startsWith(\"http" app/src/main/java/com/li63050a/linuxandroid/InstanceConfig.kt
grep -n "trimEnd" app/src/main/java/com/li63050a/linuxandroid/InstanceConfig.kt
```

**其它要点**：

- 自定义源是**额外候选**，排在最前，但**清单里的 `url` + `mirrors` 始终保留作保底**。
  自定义源拼错或已下线时，下载会回退到清单地址，不会「完全下不了」。
- 只想用清单镜像 → 把自定义框清空即可，不要填 `about:blank` 之类的占位。
- 换源（APT）与下载镜像是**两件独立的事**：自定义下载镜像**不会**改变 `apt` 的源，
  反之亦然。APT 换源见 §3.10–§3.12。

---

## 6. 清单解析问题

### 6.1 `清单解析失败: 不支持的清单 version=N（期望 2）`

**这是本轮新增的校验，属于正常工作。**

**原因**：`rootfs_manifest.json` 顶层 `version` 不是 2（或字段缺失）。

**解决**：

- 自己改了清单 → 改回 `"version": 2`
- 从更新版本的应用拿到了清单 → 要么升级应用，要么把清单降级到 v2 结构

> 若确实要演进清单结构，必须**同步**提升 `ManifestLoader.SUPPORTED_VERSION`。

### 6.2 `清单解析失败: ... format="xxx" 不受支持`

**原因**：某个版本的 `format` 不在 `tar.gz` / `tar.xz` 内。

**解决**：改成受支持格式，或扩展 `RootfsExtractor` 并同步支持集合。

### 6.3 `清单解析失败: 清单未包含任何发行版`

**原因**：`distros` 是空数组。

**解决**：至少填一个家族。

### 6.4 改了清单但应用没变化

**原因**：清单是**打包在 APK 里的 assets**，不是运行时下载。

**解决**：

```bash
./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk
```

必须**重新构建并安装**，重启应用没用。

---

## 7. 崩溃问题

### 7.1 应用崩溃了，日志在哪

崩溃处理器会**先把堆栈写入 `AppLogger`，再结束进程**。

```bash
# 崩溃前的堆栈（tag: ProotTerm）
adb logcat -s ProotTerm:V AndroidRuntime:E

# 重启后也可在应用内查看：抽屉 → 日志
adb shell run-as com.li63050a.linuxandroid cat files/logs/app.log | tail -100
```

**期望顺序**：`ProotTerm` 先输出完整堆栈 → 然后 `AndroidRuntime` 才报 fatal。

### 7.2 安装/启动失败只是 Toast，没有堆栈

**这是设计**：所有安装/启动流程都包在 `try-catch` 里，失败只 Toast 不闪退。堆栈在日志里：

```bash
adb logcat -s ProotTerm:V | grep -E "^.*E/"
```

在应用内日志页也能看到（带 `E` 级别标记）。

### 7.3 分享日志时崩溃

**原因**：用了 `file://` URI（API 24+ 抛 `FileUriExposedException`）。

**这是硬性约束第 12 条**：必须用 FileProvider `content://`。检查 `res/xml/file_paths.xml` 与 `LogActivity` 的 `FileProvider.getUriForFile`。

---

## 8. 快速对照表

| 症状 | 最可能原因 | 跳到 |
| --- | --- | --- |
| 点启动没反应 | **x86 设备**（非 arm64） | §2.1 |
| 缺少 PRoot 二进制 | `useLegacyPackaging` 被改 | §2.2 |
| 无提示符无回显 | 非 PTY，**预期行为** | §2.5 |
| 外部存储装的系统启动报权限拒绝 | **Android 11+ `/Android/data` noexec，平台限制** | §2.7 |
| 改了存储位置系统列表空了 | 数据在另一位置，未丢失 | §2.8 |
| 选了外部却装到内部 | 外部不可用，自动降级 | §2.9 |
| 一直下载失败 | 源站失效 | §3.1 |
| 每次从头下载 | `.part` 被删或服务端不支持 Range | §3.2 |
| SHA256 失败 | 内容损坏，`.part` 已被删 | §3.4 |
| 按钮卡「取消中」 | 取消逻辑回归 | §3.6 |
| 卡在「正在测速」很久 | 并发测速，**6 秒整体超时后自动回退清单顺序** | §3.7 |
| 下载总失败在同一镜像 | 服务端返回 200 而非 206（不支持 Range） | §3.8 |
| `.part.N` 分块文件残留占空间 | 中断未走清理路径；`deletePartFiles` 也会清 | §3.9 |
| 换源后 `apt update` 仍 404 | **arm64 需 `debian-ports` / `ubuntu-ports`**，或代号不对 | §3.10 |
| 「恢复原始源」说没有备份 | 从未换过或重装过；**如实报错，不是 bug** | §3.11 |
| 换源后 apt 完全不能用 | 原子写被破坏或内容写错；先恢复原源 | §3.12 |
| 符号链接变普通文件 | 误用了 java.nio | §4.3 |
| CTRL 无字母键 | 快捷键回归 | §5.1 |
| 快捷键点了没反应 / 打出 `^[[A` | 非 PTY 无回显；或快捷键变成空壳 | §5.3 |
| 弹出安全键盘 / 密码键盘 | `App.TerminalInput` 被改 | §5.4 |
| 输入法回车不发送 | `imeOptions` 混进动作型取值 | §5.5 |
| 输入法全屏编辑遮挡终端 | 缺 `flagNoExtractUi`/`flagNoFullscreen` | §5.6 |
| 输出少一半 | 缓冲上限，**预期行为** | §5.7 |
| 退到后台/划掉任务后会话没了 | **不应发生**，查 OEM 省电限制 | §5.9 |
| 开机没自启动 | 强制停止 / OEM 白名单 / 未选目标 | §5.10 |
| 会话在跑但没有通知 | Android 13+ 通知权限被拒 | §5.11 |
| 通知消失但 shell 还在 | 前台服务被系统回收，**已知取舍** | §5.12 |
| guest 的 IP 与手机相同 | PRoot 共用宿主网络栈，**正常** | §5.13 |
| 启动第二个实例把第一个杀了 | **不应发生**，多实例隔离回归 | §5.15 |
| 终端出现另一个实例的输出 | `focusedId` 过滤失效 | §5.16 |
| 同一实例启动了两次 / 点启动没反应 | 同 id 只允许一个会话；已在跑则跳终端 | §5.17 |
| 实例设置被重置为默认 | `instances.json` 损坏或缺失 | §5.18 |
| 设了资源限制后 shell 起不来 | `-v` 太低（无符号换算/下限约 64MB） | §5.19 |
| 改了资源限制没变化 | **只对新会话生效**，需重启该实例 | §5.20 |
| 结束会话后仍有残留 shell | ulimit 包装缺 `exec` → 孤儿进程 | §5.21 |
| 自定义镜像保存不了 | 只接受 `http://` / `https://`，`file://` 被拒 | §5.22 |
| 改清单没生效 | 需重新构建安装 | §6.4 |
| 清单解析失败 | version/format 校验（新功能） | §6.1 |

---

## 9. 仍然解决不了？

收集以下信息再提 issue：

```bash
# 1) 环境
adb shell getprop ro.product.cpu.abi        # 必须 arm64-v8a
adb shell getprop ro.build.version.sdk      # API 级别
adb shell getprop ro.build.version.release

# 2) 完整日志
adb logcat -d -s ProotTerm:V > crash.log
adb shell run-as com.li63050a.linuxandroid cat files/logs/app.log >> crash.log

# 3) 文件状态
adb shell run-as com.li63050a.linuxandroid ls -lR files/ > files.log 2>&1

# 4) 空间
adb shell df -h /data
```

**提 issue 时请附上**：症状、复现步骤、`ro.product.cpu.abi`、以及日志相关片段。

> 提醒：`android:debuggable` 未开启的 release 包无法用 `run-as`。本地调试请用 `assembleDebug` 产物。
