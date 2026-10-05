# 测试指南（TESTING）

> 本项目**当前没有自动化测试**。本文档定义：在没有测试框架的前提下，如何**可复现地**验证改动。
>
> 相关：[CONTRIBUTING.md](CONTRIBUTING.md)（环境准备）、[ARCHITECTURE.md](ARCHITECTURE.md)（链路细节）、[TROUBLESHOOTING.md](TROUBLESHOOTING.md)（故障排查）

---

## 1. 现状与策略

### 1.1 为什么没有自动化测试

如实说明，不含褒贬：

- **强依赖设备能力**：核心功能（PRoot 执行 aarch64 ELF、解压真实 rootfs、进程生命周期）无法在 JVM 单元测试中验证，必须 arm64 真机。
- **无测试基础设施**：没有 `app/src/test`、`app/src/androidTest`，`app/build.gradle.kts` 中无任何测试依赖。
- **构建未经 CI 验证**：CI 只在打 tag / 手动触发时跑 `assembleRelease`，没有测试步骤。

### 1.2 因此采用的策略

| 层次 | 手段 | 覆盖 |
| --- | --- | --- |
| 静态 | `grep` 约束检查 | 禁用 API、包名一致性 |
| 编译 | `./gradlew assembleDebug` | 语法、类型、资源链接 |
| 产物 | `unzip -l` 查 APK | 4 个 `.so` 是否真的打进包 |
| 手工 | 见 §3 手测矩阵 | 功能正确性 |
| 观测 | `AppLogger` / LogActivity | 运行时行为溯源 |

> **可优先补的自动化**（按性价比排序）：
>
> 1. **`ResourceLimits.toScript(shellPath)` / `toGuestCommand(shellPath)`** —— **完全不碰
>    Android API**（整个 `ResourceLimits.kt` 只依赖 `org.json`），纯字符串/列表生成，是
>    **最容易、收益最高**的 JVM 单元测试目标。务必断言：`memoryMb=512` → `ulimit -v 524288`
>    （MB→KB 换算）、0 值不生成语句、五项全 0 返回 `null`、脚本末尾必须是 `exec "…"`、
>    **`toGuestCommand` 必须返回 3 个独立元素 `["/bin/sh", "-c", 脚本]`**（§7.10 RL1–RL6）。
> 2. **`ManifestLoader.parse(json)`** —— 纯函数（不碰 Android API），清单 version/format
>    校验都在这里。
> 3. **`InstanceConfig` / `ResourceLimits` 的 `toJson()` / `fromJson()` 往返** ——
>    同样是纯 JSON，能覆盖 §7.10 RL14 与 §7.14 IC3 的持久化契约。
> 4. `RootfsManager` 的目录计算（需少量 Context 打桩）。
>
> `AptSourceSwitcher` 的重写逻辑虽然只操作文件，但是 `object` + 私有方法，需先抽出
> 纯函数（`rewriteDeb822` / `rewriteLegacyList`）才可测；`MirrorProbeService.probe()`
> 与 `MultiPartDownloader` 需要真实网络/磁盘，难以在 JVM 单测中覆盖，仍以 §7.11 /
> §7.12 的手工矩阵为准。若引入 `testImplementation("junit:junit:4.13.2")`，1–3 项收益最高。

---

## 2. 静态与构建检查

### 2.1 一键检查脚本

```bash
cd /path/to/linuxandroid

echo "=== 1. 禁用 API（必须为空）==="
grep -rn "java\.nio\.file\|toPath()" app/src/main/java/ || echo "  PASS"

echo "=== 2. 包名污染（必须为空）==="
grep -rn "com\.example" app/src/main/java/ || echo "  PASS"

echo "=== 3. SDK 26+ API 是否有分支保护 ==="
grep -rn "isAlive\|destroyForcibly\|waitFor(" app/src/main/java/ | wc -l
grep -c "Build.VERSION.SDK_INT >= Build.VERSION_CODES.O" \
  app/src/main/java/com/li63050a/linuxandroid/ProotSession.kt

echo "=== 4. 关键约束 ==="
grep -c "useLegacyPackaging = true" app/build.gradle.kts   # 期望 1
grep -c 'abiFilters += "arm64-v8a"' app/build.gradle.kts   # 期望 1
grep -c 'android.permission.INTERNET' app/src/main/AndroidManifest.xml

echo "=== 5. 运行库入库情况 ==="
git ls-files | grep -E '\.so$'                              # 期望 3 个
```

### 2.2 编译

```bash
./gradlew clean assembleDebug

# 只要 Kotlin 编译
./gradlew compileDebugKotlin

# 资源链接（AAPT；XML 属性写错会在这里报）
./gradlew processDebugResources
```

### 2.3 验证 APK 内容

```bash
APK=app/build/outputs/apk/debug/app-debug.apk

# 4 个 .so 都必须在
unzip -l "$APK" | grep -E '\.so'
```

**期望输出**（4 个，一个都不能少）：

```
lib/arm64-v8a/libproot.so
lib/arm64-v8a/libproot-loader.so
assets/native/arm64-v8a/libtalloc.so.2
assets/native/arm64-v8a/libandroid-shmem.so
```

若 `lib/*.so` 缺失 → 检查 `packaging { jniLibs { useLegacyPackaging = true } }`。
若 `assets/native/**` 缺失 → 检查这两个文件是否还在 `app/src/main/assets/native/arm64-v8a/`。

```bash
# 确认没有多余 ABI 目录
unzip -l "$APK" | grep -o 'lib/[^/]*/' | sort -u    # 只应有 lib/arm64-v8a/

# 确认只申请了 INTERNET 权限
"$ANDROID_HOME"/build-tools/36.0.0/aapt2 dump permissions "$APK"
```

---

## 3. 手工测试矩阵

在 **arm64 真机**（API 24+）上执行。每项标注了**期望的日志/文案**，便于判断是「功能坏」还是「只是看起来没反应」。

### 3.1 启动与基础

| # | 操作 | 期望 |
| --- | --- | --- |
| B1 | 安装并首次启动 | 进入「发行版」页，三张卡片（Alpine/Debian/Ubuntu），无崩溃 |
| B2 | 依次开关抽屉四个入口 | 终端 / 发行版 / 日志 / 设置 均正常切换，副标题显示状态 |
| B3 | 设置页 | 显示版本 `0.0.0.1`、包名、`arm64-v8a`、SDK 范围、rootfs 绝对路径 |
| B4 | 旋转屏幕 | 界面不重建、**不丢终端输出**（已用 `configChanges` 兜住） |
| B5 | 切后台再回来（短时间） | 终端输出仍在 |

日志验证：

```bash
adb logcat -c && adb logcat -s ProotTerm:V
# 期望看到：onCreate families=3
```

### 3.2 下载安装（核心链路 A）

| # | 操作 | 期望 |
| --- | --- | --- |
| I1 | Alpine 3.20 → 下载 | 进度条推进，副标题显示 `下载 Alpine Linux … N%（x / y MB）` |
| I2 | 下载中**切到其他页再回来** | 仍显示进行中的进度（`lastDownloadPct` 恢复） |
| I3 | 下载完成 | 进度消失，按钮变「启动」，出现「卸载」 |
| I4 | 再点另一个版本 | Toast「已有下载/安装任务进行中…」 |
| I5 | **下载中点「取消」**（本次新增） | 按钮变灰显示「取消中…」→ 恢复「下载」；副标题「已取消（断点已保留，可重新下载）」 |
| I6 | 取消后重新下载 | 从断点续传（日志 `existing=` 非 0），不是从 0 开始 |
| I7 | 下载中大退应用再进 | `.part` 仍在，重新点下载可从断点继续 |
| I8 | 卸载 | rootfs 目录、`.part`、`.old` 全部清除 |

验证断点与文件布局：

```bash
# 看 .part 是否在（下载中）
adb shell run-as com.li63050a.linuxandroid ls -l files/

# 期望看到：<id>.<format>.part、tmp_<id>/（解压中）、rootfs/<id>/、native/、proot_tmp/、logs/
```

**取消功能（I5）的额外验证** —— 这是本轮新加的能力，重点测：

```bash
# 取消后确认没有卡在 Cancelling：按钮应恢复可点，且能再次发起下载
# 若按钮永久停留在「取消中」，说明 MainActivity 的 finally 兜底失效
```

### 3.3 解压（核心链路 B）

| # | 操作 | 期望 |
| --- | --- | --- |
| E1 | 安装 Alpine（`tar.gz`） | 副标题 `解压 … 已处理 N 项`，最终安装完成 |
| E2 | 安装 Debian（`tar.xz`） | 同上（走 XZ 分支） |
| E3 | 安装 Ubuntu（`tar.gz`，体积大） | 同上，耗时更长 |
| E4 | 安装后检查符号链接 | guest 内 `ls -l /bin/sh` 应为链接而非普通文件 |

**解压正确性验证**（重点：符号链接 / 权限位 / 路径穿越三者缺一不可）：

```bash
# 进入 rootfs 目录
adb shell run-as com.li63050a.linuxandroid ls -l files/rootfs/alpine-3.20/bin/sh
# 期望：lrwxrwxrwx ... -> /bin/busybox    （是链接，不是普通文件）

# 权限位抽查：应保留可执行位
adb shell run-as com.li63050a.linuxandroid ls -l files/rootfs/alpine-3.20/bin/busybox
# 期望：-rwxr-xr-x ...（不是 -rw-r--r--）

# 安装标记
adb shell run-as com.li63050a.linuxandroid ls files/rootfs/alpine-3.20/.installed
```

### 3.4 PRoot 启动（核心链路 C）

| # | 操作 | 期望 |
| --- | --- | --- |
| P1 | 点已安装版本的「启动」 | 跳到终端页，显示启动横幅 |
| P2 | 输入 `cat /etc/os-release` 发送 | 输出发行版信息 |
| P3 | **重复点同版本「启动」** | 不重启进程，提示「…的 shell 正在运行」 |
| P4 | 启动另一个已装版本 | **两个实例同时运行**，各自的终端输出互不串台（多实例，见 §7.9 MI1/MI2） |
| P5 | 未安装版本点启动 | Toast「该版本尚未安装，请先下载」（正常情况下按钮是「下载」） |

终端页启动横幅应包含 rootfs 绝对路径与「非交互 shell 无提示符/无回显」提示。

**关键：非 PTY 无提示符是预期行为，不是 bug。** 想看到提示符可输入 `sh -i`。

```bash
# 确认 PRoot 进程真的起来了
adb shell ps -A | grep -i proot

# 确认环境变量注入正确（三个都必须是绝对路径）
adb logcat -s ProotTerm:V | grep -i "PROOT_\|LD_LIBRARY"
```

### 3.5 终端 UI（核心链路 D）

| # | 操作 | 期望 |
| --- | --- | --- |
| T1 | 发送 `ls /` | 本地回显 `$ ls /` + guest 输出 |
| T2 | 未启动 shell 时发送 | 副标题「请先在「发行版」启动 shell」 |
| T3 | 搜索框输入关键词 | 输出被过滤；清空后恢复 |
| T4 | 设置页「清空终端输出」 | 输出清空 |
| T5 | 点 ESC / TAB / 方向键 | 发送对应控制序列（需在 `sh -i` 等交互模式才看得出效果） |
| T6 | **点 CTRL**（本轮修复重点） | CTRL 按钮变半透明 + **下方展开 a–z 字母行**，副标题「CTRL 已按下，再点下方字母键」 |
| T7 | CTRL 后点 `C` | 发送 `0x03`（SIGINT）；字母行收起、CTRL 复位 |
| T8 | CTRL 后再点一次 CTRL | 字母行收起、复位 |
| T9 | CTRL 后点 A–Z 全部 | 每个都应发出 `0x01`–`0x1A` |

**T6–T9 是本轮修复的缺陷**，修复前 CTRL 按下后**没有任何字母键可点**（字母行不存在）。确认方式：点 CTRL 后必须看到一行 `A B C … Z`，且每个字母都能发出对应控制字符。

> 最直观的验证：启动 shell 后执行 `sh -i`，再 `sleep 100`，然后 CTRL+C 应能中断它。

### 3.6 返回键与生命周期

| # | 操作 | 期望 |
| --- | --- | --- |
| K1 | 抽屉打开时按返回 | 关抽屉 |
| K2 | 版本页按返回 | 回发行版页 |
| K3 | 终端页、shell 运行中按返回 | 弹「保持运行 / 关闭终端」 |
| K4 | 选「保持运行」 | 回发行版页，**shell 继续运行**（切回去还在） |
| K5 | 选「关闭终端」 | shell 进程结束，输出清空 |
| K6 | 选「取消」 | 留在终端页 |

⚠️ **K4 的边界**：「保持运行」只在**应用存活期间**有效。大退应用（从最近任务划掉 / 系统回收）后会话必然丢失 —— 这是当前实现的已知限制，对话框文案已如实说明，**不是 bug**。想验证：

```bash
adb shell ps -A | grep -i proot     # K4 后应有进程
# 划掉应用后再查
adb shell ps -A | grep -i proot     # 应为空（预期行为）
```

### 3.7 清单解析与错误处理

| # | 操作 | 期望 |
| --- | --- | --- |
| M1 | 正常启动 | 三家族卡片 |
| M2 | 把 `rootfs_manifest.json` 的 `version` 改成 `3` 后重装 | Toast「清单解析失败: 不支持的清单 version=3（期望 2）…」，界面空但**不崩** |
| M3 | 把某版本 `format` 改成 `tar.zst` | Toast 提示 format 不受支持 |
| M4 | 把 `distros` 改成空数组 | Toast「清单未包含任何发行版」 |

M2–M4 是本轮新增的校验，验证时改完清单需**重新构建安装**（清单打在 APK 里）：

```bash
# 改动 assets 后必须重装（不是热更）
./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 3.8 失败与恢复

| # | 场景 | 构造方式 | 期望 |
| --- | --- | --- | --- |
| F1 | 镜像不可达 | 断网后点下载 | 自动试下一镜像；全失败则 Toast + 状态回「下载」，`.part` 保留 |
| F2 | 空间不足 | 填满存储后下载大版本 | Toast「空间不足：需要约 N MB…」，不开始下载 |
| F3 | SHA256 不符 | 临时改清单里的 sha256 | 抛「SHA256 校验失败」，**删除 `.part`**，状态回「下载」 |
| F4 | 解压中断 | 解压中强杀应用 | `tmp_<id>` 残留；下次安装开头会 `cleanupTemp` 清掉 |
| F5 | 崩溃 | 见 §4 | 堆栈先写入日志再退出，重启后可在日志页看到 |

F3 的验证要格外注意：SHA256 失败会**主动删除 `.part`**（与「续传」策略相反），因为内容已确认损坏，续传无意义。

---

## 4. 日志与崩溃验证

### 4.1 日志出口

`AppLogger` 三级输出：

| 出口 | 位置 | 容量 |
| --- | --- | --- |
| Logcat | tag `ProotTerm` | — |
| 内存环形缓冲 | 进程内 | 2000 条 |
| 文件 | `filesDir/logs/app.log` | 1MB 滚动到 `app.log.old` |

```bash
# Logcat（实时）
adb logcat -s ProotTerm:V

# 文件（需应用可调试）
adb shell run-as com.li63050a.linuxandroid cat files/logs/app.log

# 滚动日志
adb shell run-as com.li63050a.linuxandroid cat files/logs/app.log.old
```

### 4.2 验证崩溃兜底

`App.kt` 注册了未捕获异常处理器：**先写日志，再结束进程**。

```bash
adb logcat -c
# 触发一次崩溃（例如临时在 onCreate 里抛异常），然后：
adb logcat -s ProotTerm:V AndroidRuntime:E
```

期望顺序：`ProotTerm` 中出现完整堆栈 → 之后 `AndroidRuntime` 才报 fatal。应用内「日志」页在重启后应能看到那次堆栈。

### 4.3 分享日志

日志页「分享日志」必须走 FileProvider `content://`（`file://` 在 API 24+ 会抛 `FileUriExposedException`）。

```bash
# 触发分享，确认 Intent 用的是 content://
adb logcat | grep -i "content://\|FileUriExposed"
```

---

## 5. API 24 兼容性检查

minSdk 是 **24**，但开发机常是更高版本，容易漏测。三类 API 必须走分支：

| API | 最低版本 | 保护方式 |
| --- | --- | --- |
| `Process.isAlive` | 26 | `SDK_INT >= O` 分支 |
| `Process.waitFor(long, TimeUnit)` | 26 | 同上 |
| `Process.destroyForcibly` | 26 | 同上 |
| `java.nio.file.*` / `toPath()` | 26 | **禁止使用**，改用 `android.system.Os` |

`ProotSession.kt` 中已有 3 处分支保护。**若你新增了进程相关调用，务必检查**：

```bash
# 找出所有可能越界的调用点
grep -rn "isAlive\|destroyForcibly\|waitFor(\|java\.nio" app/src/main/java/

# 逐个确认上下文有 SDK_INT 判断
```

### 在 API 24/25 上实测（推荐但非必需）

```bash
# 若有 API 24 设备或 arm64 模拟器
adb shell getprop ro.build.version.sdk    # 确认是 24 或 25
# 重点回归：启动 shell → 返回键 → 关闭终端（走 destroy 的低版本分支）
```

⚠️ 注意 `ProotSession.destroy()` 在 API 24/25 上用的是**无超时**的 `p.waitFor()`，理论上可能长时间阻塞 reaper 守护线程（不阻塞 UI）。这是已知的 API 24/25 硬限制。

---

## 6. 回归清单（提交前最小集）

时间有限时，至少跑这些：

- [ ] `./gradlew clean assembleDebug` 通过
- [ ] 4 个 `.so` 在 APK 内（§2.3）
- [ ] 禁用 API 检查为空（§2.1）
- [ ] B1 启动不崩、三家族卡片在
- [ ] I1→I3 完成一次完整下载安装（建议 Alpine 3.20，体积最小）
- [ ] **I5 能取消下载且不卡在「取消中」**（本轮修复）
- [ ] **T6–T7 CTRL 字母行能展开、能发控制字符**（本轮修复）
- [ ] E4 符号链接与权限位正确
- [ ] P1→P2 启动 shell 并执行 `cat /etc/os-release`
- [ ] K3→K5 返回键三个选项行为正确
- [ ] M2 改坏清单 `version` 后不崩、有明确 Toast（本轮修复）

改动涉及的模块，对照 [MODULES.md](MODULES.md) 补充针对性测试。

---

## 7. 本轮新增功能的手工测试矩阵

> ⚠️ **本节所有用例均未在本环境执行过，是测试计划而非测试报告。**
>
> 编写本节的环境**没有 JDK、没有 Android SDK、没有任何 Android 设备**
> （实测 `which java` / `javac` 均为空，`ANDROID_HOME` 未设置），因此：
>
> - 代码**没有被编译过**，连 `./gradlew assembleDebug` 都跑不起来；
> - 没有任何一条用例在真机上跑过，下面全部是**待执行**的用例。
>
> 所有结果必须在 **arm64 真机（API 24+）** 上由人工执行并回填。若某条用例
> 预期结果与实际不符，**先确认是真缺陷，再改代码**——不要反过来改文档迁就现象。

本轮新增/变更了五块能力，对应 §7.2 ~ §7.6；§7.7 是边界与负例，§7.8 是回归清单。
**多实例轮（§7.9 ~ §7.14）** 又新增了六组：多实例并存（MI）、资源限制（RL）、镜像测速
与自定义源（MX）、多线程分块下载（MP）、APT 换源（AS）、实例设置与 `instances.json`（IC）。
这些用例的完整清单在 §8。
所有用例统一使用下列占位符：

| 占位符 | 含义 |
| --- | --- |
| `<pkg>` | `com.li63050a.linuxandroid` |
| `<id>` | 版本 id，如 `alpine-3.20` / `debian-12` |
| `<fmt>` | 归档格式，`tar.gz` / `tar.xz`（用于拼 `.part` 文件名） |
| 内部位置 | `/data/data/<pkg>`（即 `context.filesDir`，真机上常为 `/data/user/0/<pkg>`） |
| 外部位置 | `/Android/data/<pkg>/files`（即 `context.getExternalFilesDir(null)`） |
| 实例设置 | 「我的系统」→ 某项「详情」→「实例设置」（对话框 `dialog_instance_settings.xml`） |

```bash
# 本节用例的公共准备
PKG=com.li63050a.linuxandroid
APK=app/build/outputs/apk/debug/app-debug.apk
adb install -r "$APK"
adb logcat -c && adb logcat -s ProotTerm:V BootReceiver:V ProotService:V
```

### 7.1 「我的系统」页（`Screen.MY_APPS` / `view_myapps.xml` / `MyAppAdapter`）

默认屏。数据来自 `InstalledScanner.scan(currentLocation)`：扫描当前存储位置
`rootfs/` 下的**子目录**，只保留含 `.installed` 标记的那些，映射为 `MyApp`。

| 编号 | 前置条件 | 步骤 | 预期结果 | 备注 |
| --- | --- | --- | --- | --- |
| MA1 | 全新安装，两个位置都空 | 首次启动应用 | 停在「我的系统」页；显示 `myapps_empty`「还没有安装任何系统。请到「发行版」页下载一个版本。」；有「去下载系统」按钮 | 默认屏不是「发行版」页 |
| MA2 | 内部位置装有 `alpine-3.20` | 打开「我的系统」 | 列表出现 1 项：名称 / shell / 体积；无「运行中」徽标 | 体积为目录占用，量级合理即可 |
| MA3 | 同时装了 Alpine + Debian + Ubuntu | 打开「我的系统」 | 3 项全部列出，互不覆盖 | 多系统并存是核心卖点 |
| MA4 | 至少装 1 个 | 点某项「启动」 | 跳终端页，出现启动横幅；返回「我的系统」后该项带「运行中」徽标 | 徽标文案 `myapps_running` =「运行中」 |
| MA5 | 会话运行中 | 点**另一个**系统的「启动」 | **两个实例同时运行**；新项带徽标，原实例徽标**保持**；Toast「已有 N 个其他实例在运行，本次将并行启动」 | ⚠️ **事实更正**：旧文本写「旧会话结束、同时只允许一个会话」，那是旧的**进程内会话单例**时代的语义，已被 §8.17 取代。详见 §7.9 |
| MA6 | 至少装 1 个 | 点「打开终端」 | 直接跳终端页，**不重启**会话；原本就在运行的会话输出仍在 | 与 MA4 的区别：不换系统 |
| MA7 | 无任何会话 | 点「打开终端」 | 跳终端页并提示先在「我的系统」启动（`terminal_empty` 文案） | 不应崩溃 |
| MA8 | 至少装 1 个 | 点「详情」 | 显示该系统的信息（id / 路径 / shell / 体积等） | 只读，不改状态 |
| MA9 | 未安装任何东西 | 点「卸载」入口 | 无入口可点；若可达也不能崩 | 空列表下不应有残留按钮 |
| MA10 | 装有 `alpine-3.20` | 点「卸载」并确认 | 条目消失、`rootfs/<id>` 与 `.installed` 一并删除；再回「发行版」页该版本回到「下载」态 | 见 §7.7 N3 的运行中卸载 |
| MA11 | 外部位置有 2 个系统，内部位置有 1 个 | 存储位置设为**内部**打开「我的系统」 | 列表 1 项（内部那个）+ 顶部常驻横幅 `banner_other_location`：「另有 2 个系统安装在「外部存储」…」 | **横幅与列表同时出现**，见 MA13 |
| MA12 | 外部位置有系统，内部为空 | 存储位置设为**内部** | 列表为空显示 `myapps_none_in_location`（≠ `myapps_empty`），且**仍有**横幅 | 两种空态必须能区分 |
| MA13 | **两个位置都装有系统** | 打开「我的系统」 | 列表显示当前位置的项目，**同时**横幅显示「另有 N 个系统安装在「另一位置」」 | ⚠️ **回归点**：修复前当前位置非空时横幅被吞掉，这是本轮修掉的缺陷 |
| MA14 | 无任何已装系统，另一位置也为空 | 打开「我的系统」 | 显示 `myapps_empty`，**不显示**横幅 | 横幅 N=0 时必须隐藏，不能显示「另有 0 个」 |

**空态与横幅的区分**（MA11–MA14 是本轮重点）：

```bash
# 直接看磁盘上到底装在哪，用来和界面比对
adb shell run-as $PKG ls -l files/rootfs/                 # 内部位置
adb shell ls -l /sdcard/Android/data/$PKG/files/rootfs/   # 外部位置
# 每个已安装系统都应同时看到目录本体与其中的 .installed
adb shell run-as $PKG ls -l files/rootfs/<id>/.installed
```

**`MyAppAdapter` 副标题（meta 行）三态** —— 代码在
`MyAppAdapter.kt`（`fromManifest` / `manifestLoaded` 两个字段），三态**必须互不混淆**：

| 编号 | 前置条件 | 步骤 | 预期结果 | 备注 |
| --- | --- | --- | --- | --- |
| MA15 | 清单里存在该版本 | 看该项副标题 | 只显示 shell，**没有**任何后缀 | `fromManifest == true` |
| MA16 | 手工在 `rootfs/` 下造一个清单里没有的目录并写 `.installed` | 看该项副标题 | 显示 shell + 「· 自定义」（`myapps_custom_suffix`） | `fromManifest == false` 且清单**解析成功** |
| MA17 | 让清单**加载失败**（把 `assets/rootfs_manifest.json` 的 `version` 改成 `3` 后重装），再看已装项 | 看该项副标题 | 显示 shell + 「· 版本未知」（`myapps_unknown_suffix`） | `manifestLoaded == false` |

⚠️ **MA16 与 MA17 是最容易写反的一对**：「自定义」= 清单读到了、但里面没有这个版本；
「版本未知」= 清单根本没读成功。测试时**必须两种都构造一遍**，只测一种不足以说明问题。

```bash
# 造 MA16 的「自定义」条目：清单里没有的 id，但有 .installed
adb shell run-as $PKG mkdir -p files/rootfs/my-custom-root
adb shell run-as $PKG touch files/rootfs/my-custom-root/.installed
# 列表里应出现该项，副标题为「… · 自定义」，而不是「· 版本未知」

# 造 MA17 的「版本未知」：需改 assets 后重新构建安装，见 §3.7 M2
```

### 7.2 存储位置可选（`StorageLocation` / `AppPrefs.storageLocation` / `RootfsManager`）

设置页（`view_settings.xml`）单选组：**内部** `context.filesDir` vs **外部**
`context.getExternalFilesDir(null)`。**两者都不需要任何存储权限。**

| 编号 | 前置条件 | 步骤 | 预期结果 | 备注 |
| --- | --- | --- | --- | --- |
| SL1 | 任意 | 打开设置页 | 单选组可见，两项分别标注内部 / 外部及其真实路径；当前选中项与 `AppPrefs` 一致 | 路径应为绝对路径 |
| SL2 | 选中内部 | 切到外部 | 选中态即时更新；Toast/副标题提示「只影响后续安装」 | 不迁移数据 |
| SL3 | 内部已装 `alpine-3.20` | 切到外部，回「我的系统」 | 列表变空（若外部无系统）→ 显示 `myapps_none_in_location`；横幅提示内部还有 1 个 | 印证「不迁移」 |
| SL4 | 切到外部后 | 到「发行版」→ 选版本 | 头部显示「将安装到：<外部绝对路径>」（`versions_download_target_fmt`） | 安装前就能看到落点 |
| SL5 | 同 SL4 | 下载安装 `debian-12` | 下载/解压全部落在**外部**；装完后内部 `rootfs/` 里**没有**它 | 见下方 adb 校验 |
| SL6 | 内部装有 `alpine-3.20`，当前选外部 | 打开该版本的版本页 | 按钮是「重新下载」而不是「启动」，提示 `versions_installed_elsewhere_fmt` | `State.InstalledElsewhere` |
| SL7 | 同 SL6，且**外部也装了同一版本**（两个位置都装） | 看两个位置各自的版本页 | 各自位置下都显示「已安装（当前存储位置）」并可「启动」；互不干扰 | 见 §7.7 N4 |
| SL8 | **会话运行中** | 在设置页切换存储位置 | 先弹出/执行结束当前会话，再完成切换；切换后原会话不再运行 | 见 §7.7 N1 |
| SL9 | 会话未运行 | 切换存储位置 | 不弹多余对话框，直接完成 | — |
| SL10 | 外部位置不可用（见下方构造方式） | 打开设置页并选「外部」 | UI **必须如实提示已回退到内部**（`locationFallback == true`）；不能假装数据在外部 | ⚠️ 静默回退是硬性要求要暴露的点 |
| SL11 | 同 SL10 | 查看 `RootfsManager.location` / 再下载一个系统 | 实际写入内部位置；提示文案与实际落点一致 | 判定依据：`run-as ls files/rootfs/` |
| SL12 | 任意 | 检查 `native/`、`proot_tmp/`、`logs/` | 三者**始终在内部** `filesDir` 下，**不随存储位置变化** | §8.14；切到外部后再看一次 |
| SL13 | 任意 | 静态确认权限 | 应用详情页无「存储」权限；`AndroidManifest.xml` 无 `READ_/WRITE_EXTERNAL_STORAGE`、`MANAGE_EXTERNAL_STORAGE` | §8.2 |

```bash
# SL5 / SL11：确认落点
adb shell ls -l /sdcard/Android/data/$PKG/files/rootfs/     # 应有 <id> 与 <id>/.installed
adb shell run-as $PKG ls -l files/rootfs/                   # 切到外部后不应新增该 <id>

# SL12：三个固定内部位置（切到外部后仍然必须在这里）
adb shell run-as $PKG ls -ld files/native files/proot_tmp files/logs

# SL10：构造「外部不可用」的两种方式
#  方式一（推荐，不动硬件）：拔出/卸载外部存储，或在模拟器上让外部卷不可用
#  方式二：应用尚未被系统分配外部目录时冷启动（少见，不易稳定复现）
# 观察日志：
adb logcat -s ProotTerm:V | grep -i fallback
# 期望能看到 fallback=true 相关记录，且界面上有对应提示
```

**SL3 / SL6 的判定要点**：跨存储位置 `renameTo` 不可靠，所以本项目**明确不做迁移**。
「切过去系统就没了」是**设计如此**，不是数据丢失——切回来还在。测试时务必切回去确认原数据完整。

### 7.3 外部存储执行限制（Android 11+ 已知真实限制）

`/Android/data` 在 Android 11+ 由 **FUSE** 提供，通常**不允许执行文件**，proot 可能
直接以 permission denied 失败。这不是 bug，是平台限制；`ProotSession.explainStartFailure()`
会把它翻译成可操作的中文提示。

| 编号 | 前置条件 | 步骤 | 预期结果 | 备注 |
| --- | --- | --- | --- | --- |
| EX1 | Android 11+，存储位置=外部，已装 `alpine-3.20` | 点「启动」 | **两种结果都算通过**：① 正常起来；② 失败但 Toast/副标题为「请把存储位置改为内部存储后重新安装」，且**不卡在「正在启动…」** | ⚠️ **预期结论不唯一（expected-inconclusive）**：受机型/ROM/FUSE 实现影响 |
| EX2 | 同 EX1 且启动失败 | 按提示切到内部并重新安装后再启动 | 正常进入 shell | 这是 EX1 给出的恢复路径，必须真的能走通 |
| EX3 | Android 10 及以下 | 存储位置=外部，启动 | 更可能成功（`/Android/data` 在 10 之前是 sdcardfs/直接可执行） | 记录实际结果作为对照 |
| EX4 | Android 11+，位置=外部 | 应用运行中，外部被卸载/拔卡 | 对外部的访问失败要有明确错误，不能崩；`native/`/`proot_tmp/`/`logs/` 不受影响 | 见 §7.7 N5 |

```bash
# EX1：失败时确认是 FUSE 执行限制而不是别的错
adb logcat -s ProotTerm:V | grep -iE "permission denied|exec|explainStartFailure"
adb shell ls -ld /sdcard/Android/data/$PKG/files/rootfs/<id>/bin/busybox
# 手工复现执行限制（能跑成功说明该机型允许执行，EX1 更可能走①分支）：
adb shell "run-as $PKG sh -c 'files/rootfs/<id>/bin/busybox true'" ; echo "exit=$?"
```

> **EX1 的正确测法**：不要断言「必须成功」或「必须失败」，而是断言
> **「要么成功，要么失败给出可操作提示且 UI 不卡死」**。这是本环境给定的事实约束。

### 7.4 开机自启动（`BootReceiver` + `AppPrefs.autoStart/autoStartId` + `ProotService.ACTION_AUTO_START`）

链路：`BOOT_COMPLETED` / `MY_PACKAGE_REPLACED` → `BootReceiver`（**只读配置**）→
`ProotService.launch(autoStartIntent)` → 服务内**做全部校验**（查清单、`isInstalled`）。
原因：广播只有 10 秒预算，且开机瞬间外部存储可能尚未挂载。

| 编号 | 前置条件 | 步骤 | 预期结果 | 备注 |
| --- | --- | --- | --- | --- |
| BA1 | 未开启自启动 | 重启设备 | 不启动任何会话；日志 `boot: auto start disabled, skip` | — |
| BA2 | 开启自启动但未选目标 | 重启设备 | 不启动；日志 `auto start enabled but no target, skip` | `autoStartId` 为空 |
| BA3 | 开启自启动并选中**已安装**的 `alpine-3.20` | 重启设备 | 开机后自动起来一个 shell：通知栏有常驻通知，进应用终端页能看到会话在跑 | 核心正向用例 |
| BA4 | 同 BA3 | 重启后**不打开应用**，只看通知栏 | 通知在；点通知本体（不是「结束」按钮）能进入终端页并看到会话 | 服务独立于 Activity |
| BA5 | 自启动目标已**卸载** | 重启设备 | 不启动；日志出现失败/跳过原因；**不崩溃、不弹循环错误** | 见 §7.7 N6 |
| BA6 | 自启动目标在清单里已**不存在**（改清单后重装） | 重启设备 | 同上，且提示信息合理 | 服务内校验清单 |
| BA7 | 目标装在**外部**位置 | 重启设备 | 外部未挂载时按降级逻辑处理，日志有 `external storage not ready` 警告；不能崩溃 | 开机时序竞态 |
| BA8 | 自启动已开启 | 覆盖安装（`adb install -r`）触发 `MY_PACKAGE_REPLACED` | 与开机同样拉起会话 | 该 action 也需处理 |
| BA9 | 应用被用户**强制停止**（设置里「强行停止」） | 重启设备 | **不会**自启动；日志无 `boot: auto start` 记录 | ⚠️ **系统限制，不是 bug**：强制停止后系统不再投递广播，需手动打开一次应用才恢复 |
| BA10 | 未解锁设备 | 观察开机流程 | **不应**在 `LOCKED_BOOT_COMPLETED` 阶段尝试启动 | `BootReceiver` 刻意不处理该 action |
| BA11 | MIUI / EMUI / ColorOS 等定制 ROM | 在系统设置里关闭本应用的「自启动」后重启 | 不启动；开启后重启才启动 | ⚠️ 需额外授予「自启动 / 后台运行」权限，属 ROM 行为 |
| BA12 | 任意 | 检查 `ProotService.launch` 返回值 | 被系统拒绝时有日志 `service launch refused`，不抛异常 | 前台服务启动限制 |

```bash
# BA3 / BA4：不重启也能验证「服务侧拉起」路径
adb shell am broadcast -a android.intent.action.BOOT_COMPLETED -p $PKG
# （部分系统限制隐式广播，若被拦下则用真机重启验证）
adb logcat -s BootReceiver:V ProotService:V

# BA8：覆盖安装触发 MY_PACKAGE_REPLACED
adb install -r "$APK"

# BA9：强制停止后确认状态（再次重启前）
adb shell am force-stop $PKG     # 等价于设置里「强行停止」

# BA10：确认代码里没有处理 LOCKED_BOOT_COMPLETED
grep -rn "LOCKED_BOOT_COMPLETED" app/src/main/ || echo "  预期为空"
```

### 7.5 后台会话存活（`SessionManager` + `ProotService`，本轮最关键的架构约束）

> ⚠️ **事实更正（多实例轮）**：本节原本写的是一个**进程内会话单例**（类名 `Session`+`Holder`，
> 详见 §7.9 的说明）。那个类**已被删除**，取而代之的是 `SessionManager.kt`——一个持有
> `ConcurrentHashMap<String, SessionSlot>` 的 `object`，每个槽有**自己的**
> `AtomicInteger epoch`。§7.9 起的所有多实例用例取代了旧的「同时只有一个会话」语义。
> 本节表格里的 BG* 编号保持不变，但凡是「只有一个会话」的表述都按多实例重新解释。

会话归属：`SessionManager`（进程内**多实例注册表**）+ `ProotService`（前台服务，常驻通知，
channel `proot_session`，`NOTIF_ID 1001`，动作「结束」）。
**`MainActivity` 只是观察者**（`attach`/`detach`），不持有 shell；`focusedId` 只表示
**终端显示哪个实例**，不代表所有权。

| 编号 | 前置条件 | 步骤 | 预期结果 | 备注 |
| --- | --- | --- | --- | --- |
| BG1 | 会话运行中 | 看通知栏 | 常驻通知在（channel `proot_session`），含「结束」动作 | 标题见 §7.9 MI8 |
| BG2 | 会话运行中 | 点通知 | 进入应用终端页，输出连续（不重开会话） | 多实例下焦点给「正在跑的第一个」 |
| BG3 | 会话运行中 | 点通知「结束」 | shell 进程结束、通知消失、终端页显示「会话已结束」 | 通知里的「结束」**不带** `EXTRA_INSTANCE_ID`，等价于 `stopAll`，见 §7.9 MI6 |
| BG4 | 会话运行中 | 按 Home 退到后台，等 1 分钟再回来 | 会话仍在；期间 guest 内命令（如后台计数）继续推进 | 前台服务保活 |
| BG5 | 会话运行中 | **旋转屏幕** | 会话不重启，输出不丢 | `onDestroy` 不杀会话 |
| BG6 | 会话运行中 | 从终端页切到「设置」等其它页，再切回 | 会话仍运行 | 仅 detach 观察者 |
| BG7 | 会话运行中 | 触发 Activity 销毁（如开发者选项「不保留活动」） | **会话继续运行** | ⚠️ **头号回归点** |
| BG8 | 会话运行中 | `adb shell am stopservice` 停掉 `ProotService`（模拟 OEM 后台清理） | **shell 进程仍在**（`ps -A \| grep proot` 非空），通知可能消失 | ⚠️ **头号回归点**：`ProotService.onDestroy` 不得杀会话 |
| BG9 | 同 BG8，且通知已消失 | 重新打开应用 | 能重新 attach 上仍在运行的会话（或按实现给出一致状态），不出现幽灵会话 | 观察 `SessionManager` 状态机 |
| BG10 | 会话运行中 | 终端页按返回键 | 弹「后台保持运行 / 结束会话」对话框 | 见 §3.6 K3–K6；文案 `btn_keep_alive`/`btn_close_terminal` |
| BG11 | 同 BG10 | 选「后台保持运行」 | 离开终端页，**shell 继续运行**，通知仍在 | — |
| BG12 | 同 BG10 | 选「结束会话」 | shell 结束，通知消失 | 实现上先 `stopIntent(this, focusedId)` 再 `stopIntent(this)`（结束全部），见 §7.9 MI6 |
| BG13 | 会话运行中，API 33+，未授权通知 | 首次启动会话，在系统弹窗点**拒绝** | 会话**照常启动并运行**（前台服务不被阻断）；只是看不到通知 | ⚠️ 拒绝通知**不得**阻塞服务 |
| BG14 | 同 BG13 | 授权后再启动一次 | 通知正常显示 | — |
| BG15 | 会话运行中 | 把应用从最近任务划掉 | 按当前实现记录实际行为（前台服务通常仍存活） | 与旧版 §3.6 K4 的限制说明对照，行为若变化需更新文档 |

```bash
# BG3 / BG7 / BG8：进程层面的判定（每步都要看）
adb shell ps -A | grep -i proot

# BG7：Activity 销毁但会话必须活着（不保留活动 / 直接杀 Activity）
adb shell am broadcast -a android.intent.action.BOOT_COMPLETED -p $PKG   # 先拉起一个会话
adb shell ps -A | grep -i proot        # 应有
# 在终端页旋转或触发重建，然后再查一次
adb shell ps -A | grep -i proot        # 必须仍然有 → 否则 MainActivity.onDestroy 违规杀了会话

# BG8：停服务后会话必须仍在
adb shell am stopservice $PKG/.ProotService
adb shell ps -A | grep -i proot        # 必须仍然有 → 否则 ProotService.onDestroy 违规杀了会话
adb logcat -s ProotTerm:V | grep -i "onDestroy"

# BG13：确认通知权限被拒后会话仍启动
adb shell pm revoke $PKG android.permission.POST_NOTIFICATIONS
# 再启动一次会话，应正常进入 shell
adb shell dumpsys activity services $PKG | grep -i prootservice
```

**BG7 / BG8 的判定标准（务必照此执行）**：这两条不是「顺手点一下」，而是两条**硬性不变量**。
只要其中任何一条失败，就是 §8.13 被违反，必须当**阻断级缺陷**处理，而不是记成「偶发」。

### 7.6 终端 UI 与安全键盘规避（`App.TerminalInput` + 快捷键行）

命令输入框必须使用 `style="@style/App.TerminalInput"`
（`textVisiblePassword|textNoSuggestions`，`imeOptions=actionNone|flagNoExtractUi|flagNoFullscreen`，
`importantForAutofill=no`）。**禁止** `textPassword`/`textWebPassword` 变体。

| 编号 | 前置条件 | 步骤 | 预期结果 | 备注 |
| --- | --- | --- | --- | --- |
| KB1 | 任意会话 | 点命令输入框 | 弹出**普通**输入法，**不出现**系统「安全键盘」/密码型 IME（如 MIUI 安全键盘、Google 密码键盘样式） | 本轮核心修复点 |
| KB2 | 同 KB1 | 输入任意文本并观察回车键 | 回车键**不被 IME 接管**（不是「发送/搜索/前往」等动作键），不触发 `flagNoExtractUi` 的全屏编辑 | `imeOptions=actionNone` |
| KB3 | 同 KB1 | 检查自动填充 | 不弹自动填充/密码保存建议 | `importantForAutofill=no` |
| KB4 | 任意 | 静态检查布局 | `view_terminal.xml` 中该输入框走 `App.TerminalInput`；无 `textPassword`/`textWebPassword`；`themes.xml` 中样式定义与 §8.15 一致 | 见下方 grep |
| KB5 | 任意会话 | 检查快捷键行 | 两行热键：ESC / CTRL / ALT / TAB / 回车 / `\|` / `/` / HOME / END / PGUP / PGDN + 方向键 | — |
| KB6 | 任意 | 点 CTRL | 出现 `scroll_ctrl_letters` 的 a–z 行，CTRL 按钮高亮 | 见旧 §3.5 T6 |
| KB7 | 任意 | 点 ALT | 出现 a–z 行，ALT 按钮高亮 | — |
| KB8 | 交互模式（`sh -i`） | 逐个点 a–z（CTRL 已按下） | 每个都发出 **`CTRL+字母 = 0x01..0x1A`** 真实控制字符 | 用 guest 内 `cat -v` 核对 |
| KB9 | 交互模式（`sh -i`） | 逐个点 a–z（ALT 已按下） | 每个都发出 **`ALT+字母 = ESC + 字母`**（`\x1b` 后跟该字母） | 不是空壳按钮 |

**控制序列的实测方法**（在 guest 内运行，逐键核对字节）：

```sh
# guest 内的三种核对方式，任选其一
cat -v          # 控制字符显示为 ^[ ^A ^C 等
od -c           # 逐字节十六进制 + 可打印表示
read x; printf '%q\n' "$x"    # 把实际读到的内容按可复用形式打印
```

逐键**期望字节**（照此核对，不得只凭「有反应」判断）：

| 按键 | 期望字节 | 十六进制 |
| --- | --- | --- |
| ESC | `\x1b` | `1b` |
| TAB | `\x09` | `09` |
| 回车 | `\n` | `0a` |
| ↑ / ↓ / → / ← | `\x1b[A` / `\x1b[B` / `\x1b[C` / `\x1b[D` | `1b 5b 41` … |
| HOME | `\x1b[H` | `1b 5b 48` |
| END | `\x1b[F` | `1b 5b 46` |
| PGUP | `\x1b[5~` | `1b 5b 35 7e` |
| PGDN | `\x1b[6~` | `1b 5b 36 7e` |
| CTRL+A … CTRL+Z | `0x01` … `0x1A` | `01` … `1a` |
| ALT+字母 | `\x1b` + 该字母 | `1b` + `61`…`7a` |

```bash
# KB1 / KB4：静态确认输入框没有踩到安全键盘
grep -n "TerminalInput" app/src/main/res/layout/view_terminal.xml      # 应有
grep -n "textPassword\|textWebPassword" app/src/main/res/layout/view_terminal.xml
# 期望为空（§8.15 / §7.1 一键检查脚本）
grep -n "scroll_ctrl_letters" app/src/main/res/layout/view_terminal.xml

# KB8：CTRL+C 打断长命令的端到端验证
# guest 内：sh -i  然后  sleep 100  然后点 CTRL → C，应立刻中断
```

### 7.7 边界与负例

这些是最容易被忽略、也最容易出真 bug 的场景。

| 编号 | 场景 | 前置条件 | 步骤 | 预期结果 | 备注 |
| --- | --- | --- | --- | --- | --- |
| N1 | **运行中切换存储位置** | 会话运行中 | 设置页切换存储位置 | 先结束会话再切换；切换后无残留 proot 进程 | 不能出现「切换了但旧进程还在跑」 |
| N2 | 切换位置后立刻启动旧系统 | 刚切换位置，旧系统在另一位置 | 点「启动」 | 因不在当前位置而不可启动（按钮为「重新下载」/提示未安装），**不报错崩溃** | 与 SL6 呼应 |
| N3 | **卸载正在运行的系统** | 该系统会话运行中 | 点「卸载」并确认 | 要么先结束会话再删目录、要么拒绝并说明；**不得**留下运行中的进程指向已删除的 rootfs | ⚠️ 最危险的一条 |
| N4 | **同一版本装到两个位置** | 内部已装 `alpine-3.20`，切到外部 | 在外部再装一次同版本 | 两个位置各自独立、各自可启动；切位置后按钮状态正确；互不覆盖、互不删除 | 目录不同，但状态判定必须按当前位置 |
| N5 | **外部存储中途卸载** | 位置=外部，会话运行中 | 卸载/拔出外部存储 | 对外部的读写失败要有明确错误提示，不闪退；内部固定的 `native/`、`proot_tmp/`、`logs/` 不受影响 | 见 EX4 |
| N6 | **自启动目标被删除** | 自启动目标 `alpine-3.20` 已被卸载 | 重启设备 / 触发 `BOOT_COMPLETED` | 不启动、不崩溃、日志给出原因；UI 不被卡住 | 见 BA5 |
| N7 | **自启动目标换成装在外部的系统** | 目标装在外部位置 | 重启设备 | 按 `locationFallback` 逻辑处理并在日志/UI 如实反映 | 开机时外部可能未挂载 |
| N8 | **`notifyStartFailed`：清单里没有该版本** | 构造清单缺失该版本后触发启动 | 启动该会话 | 必须有**终态回调**：UI 不停留在「正在启动…」，给出明确失败文案 | `ProotService` 的 `notifyStartFailed` 路径 |
| N9 | **`notifyStartFailed`：版本未安装** | 目标版本目录不存在 / 无 `.installed` | 触发启动（含自启动路径） | 同上：不进「正在启动…」死循环，服务不卡住 | 覆盖 `isInstalled` 校验失败 |
| N10 | **fork 期间用户点「结束」** | 启动正在 prepare/fork 中 | 立刻点「结束」/通知里的结束 | 会话**不得被复活**（该实例**自己的** `slot.epoch` 代次校验生效）；最终为 IDLE | §8.13 幽灵会话；多实例下只看被测实例的 epoch，见 §7.9 MI9 |
| N11 | 连续快速点两个不同系统的「启动」 | 两个系统都已安装 | 快速交替点击 | **两个实例最终都在跑**（不再是「只剩一个」）；各自无孤儿 proot 进程；每个 id 只起一个 | ⚠️ **事实更正**：旧文本写「最终只有一个会话」，那是单例语义。同一 id 重复启动才需要先 destroy 旧的，见 §7.9 MI9 |
| N12 | 空态下点各操作 | 两个位置都没装系统 | 浏览「我的系统」页各项 | 无崩溃、无「另有 0 个」横幅 | 与 MA14 呼应 |
| N13 | 无网络下自启动 | 飞行模式 + 已装系统 | 重启设备 | 本地 shell 照常启动（不依赖网络） | 网络只在下载阶段需要 |

```bash
# N3：卸载运行中的系统后，绝不应残留指向已删目录的进程
adb shell ps -A | grep -i proot
adb shell run-as $PKG ls files/rootfs/<id>    # 应为「不存在」
# 若进程仍在且 rootfs 已删 → 真缺陷

# N8 / N9：确认 UI 脱离「正在启动…」
adb logcat -s ProotTerm:V | grep -iE "notifyStartFailed|start failed|epoch"

# N10：代次校验
adb logcat -s ProotTerm:V | grep -i "epoch"
```

### 7.8 与手机同 IP（PRoot 共享宿主网络栈）

PRoot 不做任何网络隔离，guest 直接用**宿主内核的网络栈**，所以 guest 天然就是手机的 IP。
**没有为此写任何代码**——这条用例只是确认「确实如此」，防止有人误以为需要额外配置或权限。

| 编号 | 前置条件 | 步骤 | 预期结果 | 备注 |
| --- | --- | --- | --- | --- |
| NET1 | 手机连上 Wi-Fi，会话运行中 | guest 内执行 `hostname -I` 或 `ip addr` | 看到与手机**同一个** Wi-Fi IP | 与「设置 → 关于手机 → 状态」里的 IP 比对 |
| NET2 | 同 NET1 | 手机设置里查看 Wi-Fi IP | 与 NET1 输出一致 | 记录两者截图 |
| NET3 | 会话运行中 | guest 内 `ping` 一个外网地址或用 busybox `wget` | 能通（有网时） | 无需任何额外的网络权限 |
| NET4 | 任意 | 检查 `AndroidManifest.xml` 权限 | 网络相关只有 `INTERNET`，**没有**任何 `NET_ADMIN`/`CHANGE_NETWORK_STATE` 之类 | 见下方 grep |
| NET5 | 手机切到移动数据 | 重跑 NET1 | guest 看到的 IP 随之变化（跟随宿主） | 印证共享网络栈 |

```bash
# NET1：guest 内执行（示例）
hostname -I
ip addr show 2>/dev/null || ifconfig 2>/dev/null    # 取决于镜像内可用工具

# NET4：确认没有多余的网络权限
grep -n "uses-permission" app/src/main/AndroidManifest.xml
# 期望只看到 INTERNET / RECEIVE_BOOT_COMPLETED / FOREGROUND_SERVICE(_DATA_SYNC) / POST_NOTIFICATIONS
grep -rn "NET_ADMIN\|CHANGE_NETWORK_STATE\|ACCESS_NETWORK_STATE" app/src/main/ || echo "  预期为空"
```

### 7.9 多实例并存（MI，§8.17）

> ⚠️ **本节全部用例均未在本环境执行过**（无 JDK / 无 Android SDK / 无设备，
> 代码未经编译）。这是**待执行的测试计划**，不是测试报告。写法一律是
> 「要验证 X，应做 Y，预期 Z」。

架构前提（照此判断实现对不对）：旧的**进程内会话单例**（类名 `Session`+`Holder`，现已删除）
已被 `SessionManager.kt`
取代——一个 `object`，内部是 `ConcurrentHashMap<String, SessionSlot>`，**每个槽有
自己的 `AtomicInteger epoch`**。`SessionManager` 刻意**没有**「当前会话」这种全局
概念，所有查询/操作都必须带实例 id（`stateOf(id)` / `isRunning(id)` / `snapshot(id)` /
`sendCommand(id, …)` / `stop(id)`）。`Observer` 的**三个回调全部带 `id`**：
`onSessionState(id, state, versionName)`、`onOutput(id, text)`、`onExit(id, code, reason)`。

| 编号 | 前置条件 | 步骤 | 预期结果 | 备注 |
| --- | --- | --- | --- | --- |
| MI1 | 装了 2 个系统（如 `alpine-3.20` + `debian-12`） | 启动第一个，回「我的系统」，再启动第二个 | **两个都在跑**：`runningIds()` 返回 2 个；两项都带「运行中」徽标；Toast `toast_instances_parallel`「已有 1 个其他实例在运行，本次将并行启动」 | 旧语义「启动新的会结束旧的」已作废 |
| MI2 | 同 MI1，两个实例都在跑 | 在 A 实例终端执行 `echo AAA-<随机数>`，切到 B 实例执行 `echo BBB-<随机数>`，来回切换查看 | **各自终端只出现自己的输出**，无双份、无交叉；切回去时旧输出仍在（从 `snapshot(focusedId)` 重放） | 输出按 id 分流：`onOutput(id, text)` + `MainActivity.onOutput` 的 `if (id != focusedId) return` |
| MI3 | 两个实例都在跑，焦点在 A | 记录 `ps -A \| grep proot` 的 PID 集合 → 在「我的系统」页点 B 的「打开终端」切焦点 → 再查一次 | **PID 集合完全不变**（既不新增也不减少）。切焦点**只改 `focusedId`**，绝不 start/stop 任何进程 | ⚠️ **本组头号回归点**：`focusedId` 是「终端显示哪个实例」，**不是所有权** |
| MI4 | 两个实例都在跑 | 只结束 A（A 终端返回键 → 「结束会话」，或在 A 的通知/入口结束） | A 的 proot 进程消失，**B 仍在跑**（PID 不变）；**通知仍存在**；终端焦点按实现落到 B 或显示「N 个实例运行中」文案 | `ACTION_STOP` 带 `EXTRA_INSTANCE_ID` 时只结束该实例；`hasAnyRunning()` 为 true 时不收服务 |
| MI5 | 两个实例都在跑 | 结束最后一个实例 | 通知消失、服务结束（日志 `all sessions ended, stopping service`）；`ps -A \| grep proot` **应为空** | `watchSessionEnd()` 循环条件必须是 `hasAnyRunning()`，**不是**「某实例 IDLE」 |
| MI6 | 两个实例都在跑 | 走 `stopAll` 路径：点通知栏的「结束」动作（该 action **不带** `EXTRA_INSTANCE_ID`） | **两个实例全部结束**，服务停止，通知消失；日志 `stop requested (all), stopped 2 instance(s)` | 语义与通知按钮文案「结束」一致；与 MI4 的单实例停止对照测 |
| MI7 | 两个实例都在跑 | 在「我的系统」页卸载**运行中的 A**（保留 B） | **只结束 A 并删除 A 的 rootfs**；B 照常运行、通知仍在；A 的配置从 `instances.json` 移除（`InstanceStore.remove`）；分块残留被清（`cleanupChunksFor`） | 卸载路径用 `stopIntent(context, id)` 而非 `stopIntent(context)` |
| MI8 | 分别构造 1 个、2 个、3 个实例在跑的场景 | 看通知栏标题 | 1 个 → `ProotTerm：<版本名>`；N≥2 → `ProotTerm：N 个实例运行中`；0 个 → `ProotTerm` | 通知是**一条汇总通知**（前台服务只能有一条常驻通知），不是每实例一条；正文见下 |
| MI9 | 同一系统（同 id） | 连续快速点两次「启动」 | 第二次**不重启进程**：直接跳终端页并重放输出；日志 `instance <id> already running, skip`；`ps` 中该 id **只有一个** proot 进程 | **同一个实例同时只允许一个 shell**（§8.13）；`prepare(id, name)` 会先 destroy **本实例**的旧会话，但**只影响本实例** |

通知正文（`SessionManager.notificationText()`）的优先级也一并验证：有实例在跑时，
1 个 → `notif_running`（「PRoot 会话运行中，点击返回终端」）、N≥2 → 用 `、` 连接
全部运行中的 id；没有实例在跑时才依次回退为 `notif_stopping` / `notif_starting` /
`notif_idle`。

```bash
# MI1 / MI4 / MI5：进程与服务的判定（每一步都要看）
adb shell ps -A | grep -i proot
adb shell dumpsys activity services $PKG | grep -i prootservice

# MI1：两个实例并行启动的证据
adb logcat -s ProotTerm:V | grep -E "MainActivity.*alongside|SessionManager.*started"
# 期望：starting <id2> alongside <id1>  然后  started <id2> …

# MI5：最后一个结束后服务收掉
adb logcat -s ProotTerm:V | grep "all sessions ended, stopping service"

# MI6：stopAll 的日志
adb logcat -s ProotTerm:V | grep "stop requested"

# MI7：卸载只影响一个
adb shell run-as $PKG ls files/rootfs/          # A 消失、B 仍在
adb shell run-as $PKG cat files/instances.json  # A 的记录已移除

# MI9：同一 id 不会重复起进程
adb logcat -s ProotTerm:V | grep "already running, skip"
```

**判定标准（务必照此执行）**：MI3、MI4、MI5 是三条**硬性不变量**，任何一条失败
都意味着 §8.17 被违反，必须当**阻断级缺陷**处理：

- MI3 失败 → `focusedId` 被当成了所有权（切焦点动了进程）；
- MI4 失败 → `ProotService` 在还有实例运行时错误地收了服务（`releaseIfIdle()` 少了 `hasAnyRunning()` 判断）；
- MI5 失败 → `watchSessionEnd()` 的等待条件写成了单实例语义，导致通知/服务滞留。

> 也**不要**把「只有一个实例能跑」当成预期行为。旧版本文档与本应用旧实现都是单例
> （旧的进程内会话单例），那是**已被取代**的语义；现在并存才是默认行为。

### 7.10 单实例资源限制（RL，§8.18）

> ⚠️ 同样**未执行**。以下常量全部核对过源码：`ResourceLimits.kt` 的
> `toScript()` / `toGuestCommand()`、`KEY_*`、`PRESETS`，以及 `InstanceConfig.DEFAULT_THREADS = 4`。

**传递契约（必须先验，否则后面全部无意义）**：`toGuestCommand(shellPath)` 返回的是
**argv 列表**，不是单个字符串——

| 场景 | 返回值 | proot 命令行末尾 |
| --- | --- | --- |
| 无任何限制 | `["<shell>"]`（1 个元素） | 直接是 shell 路径 |
| 有限制 | `["/bin/sh", "-c", "<脚本>"]`（**3 个独立 argv**） | `/bin/sh`、`-c`、脚本各占一个参数 |

⚠️ **必须传 3 个独立 argv，禁止把整段脚本拼成一个字符串**：proot 不是 `env`/`nice`，
它把最后一个参数当**可执行文件路径**解析（`which()` → `execvp`）。单字符串会让 proot
去找一个名字是 `ulimit -v …; exec …` 的文件，报 `'…' not found (root = …, $PATH=…)`，
**凡是设了资源限制的会话一律启动失败**。

**生成规则（要验证 X，应先构造 Y，预期得到 Z）**——`toScript(shellPath)` 按
**固定顺序**拼接，每项只在其值 > 0 时出现：

| 配置字段（JSON key） | 生成语句 | 单位换算 | 示例 |
| --- | --- | --- | --- |
| `memory_mb` | `ulimit -v <MB×1024>` | **MB → KB** | `memoryMb=512` → `-v 524288` |
| `stack_mb` | `ulimit -s <MB×1024>` | **MB → KB** | `stackMb=8` → `-s 8192` |
| `max_processes` | `ulimit -u <原值>` | 个 | `256` → `-u 256` |
| `max_open_files` | `ulimit -n <原值>` | 个 | `1024` → `-n 1024` |
| `cpu_seconds` | `ulimit -t <原值>` | **秒** | `60` → `-t 60` |

拼接顺序恒为 `-v` → `-s` → `-u` → `-n` → `-t`，分隔符 `"; "`，最后**必须**以
`exec "<shellPath>"` 收尾，整串作为 proot 的**单个参数**传入。

| 编号 | 前置条件 | 步骤 | 预期结果 | 备注 |
| --- | --- | --- | --- | --- |
| RL1 | 逐字段单独设置 | 每次只填一个字段并保存、重启该实例会话 | 日志 `limits <id>: … -> <生成串>` 与上表逐字一致（含空格、分号、引号） | 一次只填一个字段，才能定位是哪一项写错 |
| RL2 | `memoryMb=512` | 启动该实例 | 生成串含 `ulimit -v 524288`（**不是** `-v 512`） | ⚠️ **最高频的单位错误**：`-v`/`-s` 是 KB |
| RL3 | `stackMb=8` | 启动该实例 | 生成串含 `ulimit -s 8192` | 同上，MB×1024 |
| RL4 | 某项填 `0`（内存 / 栈 / 进程 / FD / CPU 时间各测一次） | 启动该实例 | 该项**完全不出现**在生成串里；其余项照常 | **0 = 不限制**；此时**不得**生成对应语句 |
| RL5 | 五个字段**全部**填 0（=`UNLIMITED`） | 启动该实例 | `toScript()` 返回 `null`，`toGuestCommand()` 返回**单个元素** `["<shell>"]`；proot 命令行末尾**没有** `ulimit`，日志**没有** `limits` 行 | 无限制 = 无包装，不是「空包装」 |
| RL6 | 任一有限制配置 | 启动后检查日志与命令行 | 脚本末尾是 `exec "<guest shell 绝对路径>"`（`exec` **必须在**）；且 proot 命令行末尾是 **3 个独立参数**（`/bin/sh`、`-c`、脚本），不是 1 个拼接串 | 见 RL8 与 §8.18；单字符串版本会让所有带限制的会话启动失败 |
| RL7 | 预设选择 | 点「预设」逐个试四个选项并保存 | 回填值与源码 `PRESETS` 一致：不限制 / 轻量（512MB,128 进程）/ 标准（1GB,256 进程,1024 FD）/ 编译用（2GB,512 进程,4096 FD）；栈与 CPU 时间**不**被预设改动 | 预设只回填表单，用户仍可微调 |
| RL8 | `memoryMb` 设成**过低**的值（如 `64`，或更极端的 `16`） | 启动该实例 | **预期失败**：动态链接器在地址空间被压得过低时无法加载 → proot/shell 起不来或立刻退出；UI 必须给出失败提示、**不卡在「正在启动…」** | 代码注释给出的有效下限约 **64MB**；这是负例，用于确认限制**真的下发到了 guest** |
| RL9 | `cpuSeconds=10` | 启动后在 guest 内跑一个持续吃 CPU 的命令（如 `sh -c 'while :; do :; done'`） | 累计 CPU 时间达到 10 秒后进程被内核**强制结束**（SIGKILL），终端给出退出信息 | ⚠️ 限制的是 **CPU 时间（秒）**，不是 CPU 核数、也不是限速。UI 文案必须如实说明；**不得**把它描述成「CPU 核数限制」 |
| RL10 | 实例已在运行，且当前限制为「不限制」 | 把 `memoryMb` 改成 `1024` 并保存，**不重启会话** | 当前会话**行为不变**（限制未生效）；日志无新的 `limits` 行；UI 提示 `limits_saved`「资源限制已保存，重启该实例后生效」 | 资源限制**只影响新建会话** |
| RL11 | 同 RL10 | 结束该实例会话，再重新启动 | 新会话日志出现新的 `limits <id> …` 行，且与界面填写一致 | 与 RL10 配对：证明「不是不生效，是要重启」 |
| RL12 | 任意 | 保存后在实例设置里重开对话框，再看「详情」 | 表单值与保存值一致；详情弹窗出现 `limits_summary_fmt` 摘要（如「内存 1024MB · 进程 256」），无限制时显示「不限制」 | 摘要与生成串是两套文案，都要对 |
| RL13 | 任意 | 保存后直接看 `files/instances.json` | 该实例对象里 `limits` 的五个 key（`memory_mb` / `max_processes` / `max_open_files` / `cpu_seconds` / `stack_mb`）与界面一致；**只写这一个实例**，其他实例的记录不受影响 | §8.17：实例级配置不得写成全局 |
| RL14 | 已保存过限制的实例 | 杀进程后重启应用，再看实例设置 | 限制值**从 `instances.json` 完整读回**（round-trip）；应用显示的值与保存前一致 | 覆盖 `toJson()` / `fromJson()` 往返 |
| RL15 | 多实例，各自不同的限制 | 两个实例都启动 | 日志中两条 `limits <id>` 行**互不相同**，各用各的配置 | 每个实例独立的资源限制 |

```bash
# RL1–RL7：看实际下发的包装串（这是唯一权威证据，别只看界面）
adb logcat -s ProotTerm:V | grep "limits"
# 例：limits alpine-3.20: 内存 512MB · 进程 256 -> ulimit -v 524288 -u 256; exec "/bin/sh"

# RL5：无限制时应完全没有这一行
adb logcat -s ProotTerm:V | grep -c "limits <id>"     # 期望 0

# RL13 / RL14：持久化内容
adb shell run-as $PKG cat files/instances.json

# RL6：确认 proot 命令行末尾就是 exec 包装（tag Proot 的 start 行）
adb logcat -s ProotTerm:V | grep "start <id> cmd="

# RL8：确认确实是「限制太低」而不是别的错
adb logcat -s ProotTerm:V | grep -iE "cannot execute|error while loading|cannot allocate"

# RL9：CPU 时间上限生效（guest 内）
sh -c 'while :; do :; done'      # 应在约 cpuSeconds 秒后被强制结束
```

**静态核对（不需要设备即可做，但仍不代表用例已执行）**：

```bash
# 生成逻辑与常量
grep -n "ulimit -" app/src/main/java/com/li63050a/linuxandroid/ResourceLimits.kt
grep -n "1024\|exec \"" app/src/main/java/com/li63050a/linuxandroid/ResourceLimits.kt
# 必须能看到 memoryMb.toLong() * 1024、stackMb.toLong() * 1024，以及 "; exec \"\$shellPath\""
```

### 7.11 镜像测速与自定义镜像源（MX，§8.20）

> ⚠️ 同样**未执行**。超时常量已核对源码 `MirrorProbe.kt`：单镜像 **4000ms**、
> 整体 **6000ms**；并发用 `coroutineScope + async`，**不是**串行等待。

| 编号 | 前置条件 | 步骤 | 预期结果 | 备注 |
| --- | --- | --- | --- | --- |
| MX1 | 网络正常，某版本有 ≥2 个候选地址 | 勾选「自动优选」后点下载 | 副标题先出现「正在测速选择最快镜像 …」，随后「已选择镜像：<url>」，**总耗时 ≤ 约 6 秒**；日志 `MirrorProbe: ranked: …` 按延迟**升序** | 并发探测；串行会让「优选」比下载还慢 |
| MX2 | 同上 | 查看下载实际使用的地址 | 实际首选 = 测速排名第一的地址（日志 `Install: ranked mirrors for <id>: [...]` 的第一项就是下载首选） | 测速结果必须**真的被用上**，不能算完就丢 |
| MX3 | 断网 / 全部镜像不可达 | 点下载 | 日志 `no reachable mirror, keep manifest order`；**回退到清单原始顺序**并**照常尝试下载**；UI 提示 `mirror_probe_failed`「全部镜像均不可达，将使用清单原始顺序」 | ⚠️ **硬性要求**：测速失败**绝不能**导致无法下载 |
| MX4 | 同 MX3（整体超时场景：连得上但极慢） | 点下载 | 6 秒整体超时后日志 `total timeout, falling back to manifest order`；未完成的探测**按失败处理**，不重新发起、不反复等待 | 超时后**立即**进入下载阶段 |
| MX5 | 任意 | 在实例设置里填自定义镜像并保存 | 保存成功；下载时候选顺序里**自定义源在最前**，其后仍是清单的 `url` + `mirrors` | 自定义源是**额外候选**，不是替换清单——清单地址是保底 |
| MX6 | 同 MX5 | 保存后下载 | 日志里候选列表第一项是自定义地址；若自定义源失败，**仍会回退到清单地址**继续下载 | 用户填错地址不该导致完全下不了 |
| MX7 | 任意 | 在自定义镜像框里输入 `file:///sdcard/x.tar.gz` 并保存 | **拒绝保存**，Toast `mirror_custom_invalid`「地址必须以 http:// 或 https:// 开头」；配置**不被写入** | §8.20：写入前必须做 scheme 校验 |
| MX8 | 同 MX7 | 依次试 `ftp://…`、`content://…`、裸路径 `/data/xxx`、`HTTP://EXAMPLE.COM` | 前三个被拒；`HTTP://` **大写也应通过**（校验前先 `lowercase()`）；保存时去掉尾部 `/` | 规范化逻辑：`trim()` → scheme 校验 → `trimEnd('/')` |
| MX9 | 任意 | 自定义框留空并保存 | **允许**（空 = 不自定义），配置里 `mirror_override` 为空串；下载只用清单地址 | 空串是合法值，不是错误 |
| MX10 | 某版本有 ≥2 个候选 | 在实例设置点「测速」按钮 | 显示「正在测速 …」→ 逐行列出 `url — N ms`；期间按钮**置灰**，完成后恢复 | 与下载时的自动优选共用同一套探测 |
| MX11 | 全部候选不可达 | 点「测速」 | 显示「全部镜像均不可达，将使用清单原始顺序」；不崩、不卡死 | 与 MX3 同一路径 |
| MX12 | 任意 | 关掉「自动优选」开关后下载 | **不出现**「正在测速」文案，直接用候选顺序（自定义源仍在前） | 关掉开关即跳过 `MirrorProbeService.rank()` |

```bash
# MX1 / MX2 / MX3 / MX4：探测日志（这是唯一权威证据）
adb logcat -s ProotTerm:V | grep -E "MirrorProbe|Install.*ranked"
#   ranked: <url>=123ms, <url>=456ms
#   unreachable: <url>(<原因>)
#   total timeout, falling back to manifest order
#   no reachable mirror, keep manifest order

# MX7 / MX8：静态确认 scheme 校验与规范化
grep -n "startsWith(\"http" app/src/main/java/com/li63050a/linuxandroid/InstanceConfig.kt
grep -n "trimEnd" app/src/main/java/com/li63050a/linuxandroid/InstanceConfig.kt
```

> **不要**把「测速结果快」当成内容可信的依据。测速只是**排序建议**，完整性仍然
> 只由 SHA256（清单非空时）保证——这条必须一起验（见 §3.8 F3 一类的负例）。

### 7.12 多线程分块下载（MP，§8.19）

> ⚠️ 同样**未执行**。常量已核对源码 `MultiPartDownloader.kt`：
> `MAX_THREADS = 8`、`MIN_MULTIPART_BYTES = 8MB`、缓冲 128KB、进度节流 200ms；
> `InstanceConfig.DEFAULT_THREADS = 4`。

分块文件命名规则（**逐字核对**）：单线程 `.part` 为 `<id>.<format>.part`，
分块为 `<id>.<format>.part.<index>`（index 从 **0** 开始），**每块还配一个来源标记
`<id>.<format>.part.<index>.src`**（内容为 `"<url>|<total>"`），合并中间文件为
`<id>.<format>.part.merge`。

⚠️ **分块复用的判定是「长度 + 来源」两个条件，缺一不可**（§8.19）：仅凭长度复用是
危险的——分块会跨应用重启、跨镜像留存，而镜像顺序由测速决定、本身就不稳定；若新镜像
的文件恰好字节数相同但内容不同，旧块会被当成有效数据参与合并，**总长度校验也能通过**，
于是一个「长度正确但内容损坏」的归档被交到解压阶段。Debian/Ubuntu 在清单里 `sha256`
为空，没有完整性网兜底，所以必须在这里挡住。

| 编号 | 前置条件 | 步骤 | 预期结果 | 备注 |
| --- | --- | --- | --- | --- |
| MP1 | 服务器支持 Range，文件远大于 8MB，线程数 ≥2 | 点下载 | 日志 `HEAD len=<总长> accept-ranges=...` → `multipart id=… total=… threads=N url=…`；下载期间 `files/` 下同时出现多个 `.part.<i>`；完成后只剩 `rootfs/<id>`，**分块与 `.part` 全部消失** | 成功路径 |
| MP2 | 同 MP1 | 下载完成后校验产物 | 合并后的 `.part` 长度 == 服务端总长；日志 `done id=… bytes=<总长>`；SHA256（清单非空时）通过 | 合并会**校验总长度** |
| MP3 | 构造返回 **200 而非 206** 的服务器（忽略 Range 头） | 点下载 | **必须报错**，日志 `分块下载要求 206，实际 200（服务端不支持 Range）`；**不得**把整份数据写进单块后合并（那会产出损坏归档）；分块被清理 | ⚠️ **本组最关键的负例**：200 ⇒ 服务器忽略了 Range ⇒ 必须失败而不是「照写」 |
| MP4 | 同 MP3，且该版本还有**其他**镜像 | 点下载 | 分块失败后清理分块，日志 `multipart failed on <url>, falling back to N other mirror(s)`，随后用**其余地址**走单线程重试；最终能下载成功 | 多镜像回退不能因为走了分块路径而失效 |
| MP5 | 下载进行中 | 查看 `files/` 下的分块文件名 | 严格形如 `<id>.<format>.part.0`、`.1`、`.2`…；**没有**别的命名 | 命名是契约，续传与清理都依赖它 |
| MP6 | 构造「合并后长度 ≠ 服务端总长」的场景（如服务端少发若干字节） | 点下载 | 日志 `合并后长度不符 X/Y，已丢弃（请重试）`；**整个 `.part` 被丢弃**（不是保留半成品）；`merge` 中间文件也删除 | 残缺归档在解压期会报难以理解的 tar 错误，因此宁可在这里整份失败 |
| MP7 | 下载进行中（多分块已建立） | 点「取消」 | 按钮短暂「取消中…」→ 恢复「下载」；`files/` 下**所有** `.part.<i>` 与 `.part.merge` **全部消失**；日志有 `Downloader: cancel requested` 与分块清理记录 | §8.19：取消必须清理**所有**分块残留 |
| MP8 | 用 MP7 的取消结果 | 检查单线程 `.part` | `.part` **保留**（供续传），只清分块 | 取消 ≠ 丢弃断点；见 §3.6 |
| MP9 | 已存在一个**完整**的分块文件（长度 == 该块应有长度）**且 `.src` 标记的 `url\|total` 与本次一致** | 重新下载，且仍走分块路径、同一镜像 | 该块被**直接复用**：日志 `chunk[a-b] reused (<N>B, same source)`，不重复消耗流量；其余块正常下载 | 复用需**同时**满足 长度正确 + `.src` 的 URL 与总长一致 |
| MP9b | 同 MP9，但**换了镜像**（或该块没有 `.src` 标记） | 重新下载（走到分块路径） | 该块**不被复用**：日志 `chunk[a-b] discarded (source/length mismatch)`，该块被删除后整块重下 | ⚠️ **负例，最关键的一条**：仅凭长度复用会产出「长度正确但内容损坏」的归档 |
| MP10 | 线程数设为 **1** | 点下载 | 日志 `single-thread (total=… threads=1) id=…`；**不出现** `multipart` 行；走单线程 Range 续传 | `wantThreads <= 1` 直接降级 |
| MP11 | 选一个小文件（总长 < 8MB） | 点下载 | 日志 `single-thread (total=<小> threads=N)`；不切分块 | `MIN_MULTIPART_BYTES = 8MB` 门槛 |
| MP12 | 构造**长度未知**的场景（HEAD 不返回 `Content-Length`，且清单 `size` 为 0/缺失） | 点下载 | `total <= 0` → 走单线程；下载照常完成 | 长度未知不能分块（无法切边界） |
| MP13 | 线程数填**超过 8**（如 99）或**负数** | 保存 | 被夹取到 `[1, 8]`：99 → 8；负数/0 → 1（`safeThreads` / `coerceIn`）；界面与配置里存的是夹取后的值 | 不信任存储值，防止除零或空块 |
| MP14 | 线程数 4，文件远大于 32MB | 点下载 | 分块边界按 `chunkSize = (total + threads - 1) / threads` 计算（向上取整），最后一块是**余数**；合并后的长度正好等于总长 | 合并顺序必须按 index 升序 |
| MP15 | 任意 | 下载完成后检查 `files/` | **没有** `.part.merge` 残留，没有 `.part.<i>`，**也没有 `.part.<i>.src` 残留** | 合并成功后 `cleanupChunks()`；标记文件必须随分块一起删 |

```bash
# MP1 / MP5：下载期间看分块文件（需要把握好时机，或用慢速网络）
adb shell run-as $PKG ls -l files/ | grep part

# MP1 / MP15：完成后必须干净
adb shell run-as $PKG ls -l files/ | grep -E "\.part" ; echo "exit=$?（非 0 表示没有残留）"

# MP3 / MP4 / MP6 / MP9 / MP10 / MP11 / MP12：全部可从日志判定
adb logcat -s ProotTerm:V | grep -E "MultiPart|Downloader"
#   HEAD len=… accept-ranges=…
#   multipart id=… total=… threads=…
#   single-thread (total=… threads=…) id=…
#   chunk[a-b] reused (…B, same source)      ← 长度+来源都匹配才复用
#   chunk[a-b] discarded (source/length mismatch)  ← 来源不符，丢弃重下
#   分块下载要求 206，实际 200 …
#   合并后长度不符 X/Y，已丢弃（请重试）
#   multipart failed on <url>, falling back to N other mirror(s)
#   done id=… bytes=…

# MP7 / MP8：取消后的清理
adb logcat -s ProotTerm:V | grep -iE "cancel|cleaned chunk"
```

**判定要点**：MP3、MP6、MP7 是三条**硬性不变量**——分别对应「200 必须报错」、
「长度不符必须整份丢弃」、「取消必须清理全部分块」。任何一条失败都会产出
**损坏的归档**或**无界增长的分块垃圾**，按阻断级缺陷处理。

### 7.13 APT 换源（AS，§8.21）

> ⚠️ 同样**未执行**。常量已核对源码 `AptSourceSwitcher.kt`：备份后缀
> `BACKUP_SUFFIX = ".prootterm.bak"`；deb822 目录 `etc/apt/sources.list.d`；
> 旧式 `etc/apt/sources.list`；Alpine `etc/apk/repositories`。

**改哪个文件**（策略是「优先 deb822，没有才改旧式」）：

| 发行版 / 格式 | 目标文件 | 关键处理 |
| --- | --- | --- |
| Debian（deb822，12+ 默认） | `etc/apt/sources.list.d/*.sources` | 重写 `URIs:` → `debian-ports` 基址；`Suites:` → 代号；`Components:` → `main contrib non-free`；**`Signed-By:` 等其余字段原样保留** |
| Debian（旧式） | `etc/apt/sources.list` | 只改 `deb ` / `deb-src ` 开头的行，`#` 注释行不动 |
| Ubuntu（deb822 / 旧式） | 同上 | 基址用 `ubuntu-ports`；`Components:` → `main restricted universe multiverse` |
| Alpine | `etc/apk/repositories` | 取原行里 `/alpine/` 之后的部分（`branch/repo`）拼到新镜像；无法识别的行**保持原样并记日志** |

| 编号 | 前置条件 | 步骤 | 预期结果 | 备注 |
| --- | --- | --- | --- | --- |
| AS1 | Debian 12（deb822），已安装未运行 | 实例设置 → 换源 → 选「清华 TUNA」 | Toast `apt_switch_ok_fmt`；状态变为「已换源：清华 TUNA」；`*.sources` 的 `URIs:` 变成 `https://mirrors.tuna.tsinghua.edu.cn/debian-ports` | arm64 走 **debian-ports**，不是 `debian` |
| AS2 | 同 AS1 | 对比换源前后的 `*.sources` 全文 | **除 `URIs:` / `Suites:` / `Components:` 三行外，其余行逐字不变**；`Signed-By:` 必须原样保留 | ⚠️ 签名密钥路径改错会让 apt **拒绝整个源** |
| AS3 | Debian/Ubuntu 只有旧式 `sources.list`（无 `*.sources`） | 换源 | 走旧式路径，日志 `legacy rewritten for <id> -> …`；`deb`/`deb-src` 行被改写，`#` 注释行与空行**保持不变** | `deb-src` 也必须一起改写 |
| AS4 | Alpine 3.20 | 换源 | 日志 `alpine rewritten xN for <id>`；`etc/apk/repositories` 中的 `https://dl-cdn.alpinelinux.org/alpine/v3.20/main` → `<镜像>/alpine/v3.20/main` | **branch 从原文件解析**（`v3.20`），不写死版本 |
| AS5 | 同 AS4 | 检查无法识别的行 | 若某行不含 `/alpine/`（自定义 CDN）→ 日志 `跳过无法识别的 apk 仓库行: …`，该行**原样保留**；若**所有**行都无法识别 → 返回 Failure「没有可识别的 apk 仓库行」 | 不猜、不静默丢行 |
| AS6 | 已换过一次源（存在 `.prootterm.bak`） | **再换一次**（换另一个镜像） | 第二次**不覆盖**备份：日志 `备份已存在，保留原始备份: <name>.prootterm.bak`；备份内容仍是**最初的原始源**；主文件内容变成第二个镜像 | ⚠️ **幂等性的核心**：覆盖备份会让原始源永久丢失、再也回不去 |
| AS7 | 同 AS6 | 连续换 3 次不同镜像后，点「恢复原始源」 | 恢复出来的是**最初**的原始源（不是第二次的那个）；备份文件被删除；状态回到「当前使用发行版原始源」 | 与 AS6 配对验证 |
| AS8 | 已换过源 | 点「恢复原始源」 | Toast `apt_restore_ok_fmt`；`deb822` / 旧式 / Alpine 三条路径都要分别验证；恢复后源文件与备份内容**逐字一致** | — |
| AS9 | **从未换过源**（全新安装，无 `.prootterm.bak`） | 点「恢复原始源」 | **如实报错**：`apt_restore_failed_fmt`「没有找到任何备份（.prootterm.bak），无法恢复。可能从未换过源，或 rootfs 是重新安装的。」 | ⚠️ **不得假装成功**；状态**不**改变 |
| AS10 | 任意 | 换源后检查目录 | 目标文件与备份**同时存在**；**没有** `.tmp` 残留（正常完成时） | 写入是「临时文件 + rename」的**原子**替换 |
| AS11 | 实例**正在运行**时换源 | 点换源 | 先 Toast `apt_running_warning`「该实例正在运行，换源后需重启会话才会生效」，再执行换源；换源本身成功 | 换源只改文本文件，不启动 guest；但 guest 内 apt 可能已缓存旧列表 |
| AS12 | 同 AS11 | 换源后**不重启**会话，直接在 guest 内 `apt update` | 可能仍读到旧源（缓存）——这是提示存在的原因；**重启该实例会话**后再 `apt update` 才反映新源 | 与 RL10/RL11 同类的「需重启生效」语义 |
| AS13 | 任意 | 换源后确认实例配置 | `instances.json` 里该实例的 `apt_mirror` = 镜像 id（如 `"tuna"`）；恢复后变回 `null` | 失败时**不写**配置：避免界面显示「已换源」而文件其实没改 |
| AS14 | 不支持的发行版（如自造的其它 rootfs，`/etc/os-release` 的 `ID` 不是 debian/ubuntu/alpine） | 换源 | 返回 Failure「无法识别的发行版（/etc/os-release 的 ID="…"）。目前仅支持 Debian / Ubuntu / Alpine 自动换源。」 | 不猜发行版 |
| AS15 | Debian/Ubuntu 但既无 `*.sources` 也无 `sources.list` | 换源 | Failure「未找到 etc/apt/sources.list 或 etc/apt/sources.list.d/*.sources，无法换源」 | 明确的失败原因，不是「失败」二字 |
| AS16 | 两个实例分别换不同的源 | 分别换源 | 各自 rootfs 内的源文件互不影响；`instances.json` 中两条 `apt_mirror` 各自独立 | §8.17：换源设置是实例级的 |
| AS17 | 换源后确认代号来源 | 看换源日志与 `os-release` | `Suites:` 用的是 rootfs 内 `/etc/os-release` 的 `VERSION_CODENAME`（如 `bookworm`），**不是**硬编码 | §8.21 明确要求不得硬编码猜测 |
| AS18 | 换源后 `apt update` | guest 内执行 | arm64 上**必须**用 ports 仓库才能成功；若 404，按 §3.10 排查（debian-ports / ubuntu-ports / 代号 / 需重启会话） | 换源「成功」≠ apt 能用，这两件事都要测 |

```bash
# AS1–AS5：直接读 rootfs 内文件核对（宿主机侧，不需要启动 guest）
adb shell run-as $PKG cat files/rootfs/<id>/etc/apt/sources.list.d/<name>.sources
adb shell run-as $PKG cat files/rootfs/<id>/etc/apt/sources.list
adb shell run-as $PKG cat files/rootfs/<id>/etc/apk/repositories
adb shell run-as $PKG cat files/rootfs/<id>/etc/os-release        # VERSION_CODENAME

# AS6 / AS7 / AS9：备份是否被保护、恢复是否如实
adb shell run-as $PKG ls -l files/rootfs/<id>/etc/apt/sources.list.d/
adb logcat -s ProotTerm:V | grep AptSwitch
#   备份已存在，保留原始备份: <name>.prootterm.bak
#   deb822 rewritten x1 for <id> -> https://mirrors.<...>/debian-ports
#   legacy rewritten for <id> -> …
#   alpine rewritten xN for <id>

# AS10：原子写没有留下 .tmp
adb shell run-as $PKG ls -l files/rootfs/<id>/etc/apt/sources.list.d/ | grep tmp

# AS13：实例级配置
adb shell run-as $PKG cat files/instances.json

# AS17：静态确认代号来自 os-release、且内置镜像是 ports 基址
grep -n "VERSION_CODENAME" app/src/main/java/com/li63050a/linuxandroid/AptSourceSwitcher.kt
grep -n "debian-ports\|ubuntu-ports" app/src/main/java/com/li63050a/linuxandroid/AptSourceSwitcher.kt
```

**判定要点**：AS2（保留 `Signed-By`）、AS6（不覆盖备份）、AS9（如实报错）、
AS10（原子写无半成品）四条是硬性不变量，分别对应 §8.21 的签名安全、幂等、
可逆与原子性要求。

### 7.14 实例设置与 `instances.json`（`dialog_instance_settings.xml` / `InstanceStore`）

| 编号 | 前置条件 | 步骤 | 预期结果 | 备注 |
| --- | --- | --- | --- | --- |
| IC1 | 已安装任意系统 | 「我的系统」→ 某项「详情」→ 「实例设置」 | 弹出 `dialog_instance_settings.xml` 对话框：资源限制五组输入 + 预设按钮 + 镜像区（自动优选开关 / 自定义源 / 测速 / 线程数）+ 换源区（状态 / 换源 / 恢复原始源） | 布局用 `ScrollView` 包住，小屏上必须能滚到「保存」 |
| IC2 | 未配过任何东西 | 打开对话框 | 五个限制字段**显示为空串**（不是 `"0"`），占位文本是「不限制」；线程数显示默认 **4**；镜像框为空；自动优选**默认开启** | 0 显示为空是为了避免用户以为必须填数字 |
| IC3 | 已保存过配置 | 关掉再打开对话框 | 所有字段回填为保存值（`fill(...)`） | round-trip |
| IC4 | 任意 | 在任一限制字段填**非数字**（如 `abc`）或负数后保存 | Toast `limits_invalid`「请输入非负整数」；**整体不保存**（不做部分保存） | 半套限制比不限制更难排查 |
| IC5 | 任意 | 填好自定义镜像 + 线程数 + 限制后保存 | 一次 `put()` 全部落盘；日志 `instance <id> saved limits=… mirror='…' auto=… threads=…`；Toast `limits_saved` | — |
| IC6 | 任意 | 打开「详情」 | 出现 `limits_summary_fmt` 摘要行；无限制时显示「不限制」 | 与 §7.10 RL12 对应 |
| IC7 | 装了 2 个系统 | 给 A 和 B 分别设不同的限制/镜像/线程数 | 两份配置互不覆盖；`instances.json` 里是**两条**记录 | §8.17 |
| IC8 | 任意 | 卸载某系统后看 `instances.json` | 该实例的记录被移除（`InstanceStore.remove`）；其他实例记录不受影响 | 防止文件无限增长 |
| IC9 | 手工在 `files/instances.json` 里写坏 JSON（如删掉一个 `}`）后重启应用 | 打开任一实例设置 | 应用**不崩**；日志 `InstanceStore: 读取 instances.json 失败，回退为空配置`；配置显示为默认值 | 配置坏了不能拖垮应用；见 §5.18 |

```bash
# IC1 / IC7 / IC8：配置文件内容
adb shell run-as $PKG cat files/instances.json
# 期望：JSON 数组，每实例一项，字段 id / display_name / limits{…} /
#       mirror_override / prefer_auto_mirror / download_threads / apt_mirror

# IC5：保存日志
adb logcat -s ProotTerm:V | grep "saved limits"

# IC9：损坏后仍能启动
adb logcat -s ProotTerm:V | grep InstanceStore
```

> `instances.json` 固定在**内部** `filesDir`，**不随存储位置切换**（与 `native/`、
> `proot_tmp/`、`logs/` 同理）。因此「切到外部位置后实例配置还在」是**正确行为**，
> 不要把它当成配置串位。

---

## 8. 本轮回归清单（提交前最小集）

在旧 §6 之外，本轮改动**必须**额外验证以下内容。带 ⚠️ 的是硬性不变量，失败即阻断。

**架构不变量（最重要）**

- [ ] ⚠️ **BG7：`MainActivity` 销毁后会话仍存活**——`onDestroy` **禁止**调用 `SessionManager.stop()` / `stopAll()`（旧的进程内会话单例已删除）
- [ ] ⚠️ **BG8：`ProotService.onDestroy` 不结束会话**——停服务后 `ps -A | grep proot` 仍非空；唯一权威结束点是通知的「结束」（`ACTION_STOP`）
- [ ] ⚠️ N10：fork 期间点「结束」后会话不被复活（**该实例自己的** `slot.epoch` 代次校验）
- [ ] ⚠️ N11：快速交替启动两个不同系统后**两个都在跑**，且每个 id 只起一个（`prepare()` 只 destroy 本实例的旧会话）

**多系统（§7.1）**

- [ ] MA1 空态文案为 `myapps_empty`；MA12 为 `myapps_none_in_location`（两者不可混用）
- [ ] ⚠️ MA13：当前位置**也有**系统时，`banner_other_location` 横幅仍要显示（本轮修复的缺陷）
- [ ] MA14：另一位置为 0 个系统时横幅隐藏
- [ ] ⚠️ MA16 / MA17：「· 自定义」与「· 版本未知」两种副标题各构造一次，均不混淆
- [ ] MA5 / MI1：两个实例可**同时**运行且各自带「运行中」徽标（多实例，§7.9）

**存储位置（§7.2 / §7.3）**

- [ ] SL3 / SL6：切换位置后旧系统显示「装在别处」且不迁移数据，切回去数据完好
- [ ] SL10：外部不可用时**如实**提示已回退内部（不得静默）
- [ ] SL12：`native/`、`proot_tmp/`、`logs/` 始终在内部
- [ ] N1：运行中切换位置会先结束会话
- [ ] N3：卸载正在运行的系统不留下悬空进程
- [ ] N4：同一版本装两个位置互不干扰
- [ ] EX1：Android 11+ 外部启动失败时提示「请把存储位置改为内部存储后重新安装」，且 UI 不卡在「正在启动…」（**结果不唯一，属预期**）

**自启动（§7.4）**

- [ ] BA3 / BA4：真机重启后自动起会话，且不开应用也能从通知进入
- [ ] BA5 / N6：目标已卸载时安全跳过
- [ ] BA9：强制停止后不自启动（**已知系统限制，不是 bug**）
- [ ] BA10：`grep -rn LOCKED_BOOT_COMPLETED app/src/main/` 为空
- [ ] BA11：定制 ROM 需额外授权「自启动/后台运行」（文档如实说明）

**终端与交互（§7.5 / §7.6）**

- [ ] BG3：通知「结束」能真正结束会话
- [ ] BG13：API 33+ **拒绝**通知权限后会话仍能启动（不得阻塞服务）
- [ ] KB1 / KB2：点输入框**不弹**安全键盘，回车未被 IME 接管
- [ ] KB4：`grep -n "textPassword\|textWebPassword" view_terminal.xml` 为空
- [ ] KB8 / KB9：CTRL 与 ALT 字母键发出**真实**控制序列（用 `cat -v` / `od -c` 核对）

**失败路径**

- [ ] ⚠️ N8：清单缺该版本时 UI 不停留在「正在启动…」
- [ ] ⚠️ N9：版本未安装时同上

**多实例（MI，§7.9，§8.17）**

- [ ] ⚠️ MI3：切换终端焦点（`focusedId`）**不启动也不结束任何进程**——PID 集合前后完全不变
- [ ] ⚠️ MI4：结束其中一个实例后，**另一个仍在跑且通知仍在**（`hasAnyRunning()` 判断生效）
- [ ] ⚠️ MI5：结束**最后一个**实例后服务与通知才消失；日志 `all sessions ended, stopping service`
- [ ] MI1：启动第二个实例**不再**结束第一个（`toast_instances_parallel`）
- [ ] MI2：两个实例的输出互不串台（`onOutput` 按 id 过滤；切回去用 `snapshot(focusedId)` 重放）
- [ ] MI6：通知栏「结束」（`stopAll`）结束**全部**实例；单实例结束走 `stopIntent(context, id)`
- [ ] MI7：卸载运行中的实例**只**结束它自己，其他实例不受影响
- [ ] MI8：通知标题 1 个实例显示版本名、N≥2 显示「N 个实例运行中」
- [ ] MI9：同一 id 不允许两个 shell（`already running, skip`）；`prepare(id)` 只 destroy 本实例的旧会话
- [ ] 静态：`SessionManager` 里不存在无参的「当前会话」访问器（不得新增 `sessionOrNull()` 式全局入口）

**资源限制（RL，§7.10，§8.18）**

- [ ] ⚠️ RL6a：`toGuestCommand()` 必须返回 **3 个独立 argv** `["/bin/sh", "-c", 脚本]`——单字符串会让 proot 报 `'…' not found`，**凡设限制的会话一律启动失败**
- [ ] ⚠️ RL6b：脚本末尾是 `exec "<shell 路径>"`，**`exec` 不可省略**（否则 `destroy()` 留孤儿 shell，见 §5.21）
- [ ] ⚠️ RL2 / RL3：`-v` / `-s` 是 **KB**（MB×1024），512MB → `-v 524288`
- [ ] RL4 / RL5：任一项为 0 时**不生成**该语句；五项全 0 时 `toScript()` 返回 `null` 且 `toGuestCommand()` 返回单元素 `["<shell>"]`（无任何包装）
- [ ] RL8：`-v` 过低的实例**起不来**且 UI 不卡在「正在启动…」（负例，证明限制真的下发了）
- [ ] RL9：`-t` 限的是 **CPU 秒数**，超限被内核 SIGKILL；UI 文案**不得**写成「CPU 核数/限速」
- [ ] RL10 / RL11：改限制后**当前会话不变**，重启该实例后才生效（与 UI 提示一致）
- [ ] RL13 / RL14：`instances.json` 五字段 round-trip，且只写该实例的记录

**镜像（MX，§7.11，§8.20）**

- [ ] ⚠️ MX3 / MX4：测速全失败或整体超时 → **回退清单原始顺序且下载照常可用**（绝不因测速失败而无法下载）
- [ ] MX1：并发探测，总耗时 ≤ 约 6 秒（单镜像 4s / 整体 6s）
- [ ] MX5 / MX6：自定义源排在最前，但**清单地址仍保留为保底**
- [ ] MX7 / MX8：`file://` 等非 http/https 被**拒绝且不写入配置**；大写 `HTTP://` 应通过
- [ ] MX12：关掉「自动优选」后不出现「正在测速」，且不影响下载

**分块下载（MP，§7.12，§8.19）**

- [ ] ⚠️ MP3：服务端返回 **200 而非 206** 时**必须报错**，不得写出损坏归档
- [ ] ⚠️ MP6：合并长度不符 → **整份 `.part` 丢弃**（连同 `.merge`），不保留半成品
- [ ] ⚠️ MP7：取消下载清理**所有** `.part.<i>` 与 `.merge`；同时 MP8 确认单线程 `.part` 仍保留
- [ ] MP4：分块失败后换**其余镜像**单线程重试（多镜像回退在分块路径上不失效）
- [ ] MP5：分块命名严格为 `<id>.<format>.part.<index>`（index 从 0 起）
- [ ] MP9 / MP9b：只有**长度 + `.src` 来源标记**都匹配的残留分块才被复用（`reused (…B, same source)`）；换镜像后必须 `discarded (source/length mismatch)` 整块重下
- [ ] MP10 / MP11 / MP12：线程数 1 / 文件 < 8MB / 长度未知 → 均降级单线程且下载成功
- [ ] MP13：线程数被夹取到 `[1, 8]`（`MAX_THREADS = 8`）
- [ ] MP15：成功后无任何分块、`.src` 标记或 `.merge` 残留

**换源（AS，§7.13，§8.21）**

- [ ] ⚠️ AS2：deb822 重写**保留 `Signed-By`** 等其余字段（改错密钥路径会让 apt 拒绝整个源）
- [ ] ⚠️ AS6 / AS7：**重复换源不覆盖原始备份**；恢复出来的是最初的原始源
- [ ] ⚠️ AS9：**没有备份时如实报错**，不得假装恢复成功（且状态不变）
- [ ] ⚠️ AS10：写入是原子替换，正常完成时无 `.tmp` 残留在目标目录
- [ ] AS3 / AS4：旧式 `sources.list`（含 `deb-src`、保留注释行）与 Alpine `repositories`（branch 从原文件解析）都能换
- [ ] AS11：实例运行中换源要先给 `apt_running_warning` 提示
- [ ] AS17：代号取自 rootfs 内 `/etc/os-release`，**不得硬编码**
- [ ] AS18：换源后 `apt update` 在 arm64 上真的能跑通（缺 ports 基址会 404，见 §3.10）

**实例配置（IC，§7.14，§8.17）**

- [ ] IC4：任一限制字段非法 → **整体不保存**并提示
- [ ] IC7 / IC8：多实例配置互不覆盖；卸载后记录被清理
- [ ] IC9：`instances.json` 损坏不崩溃，回退默认并留有日志

**静态检查（可直接跑，与设备无关）**

- [ ] §2.1 一键检查脚本全绿
- [ ] `grep -rn "java\.nio\|toPath()" app/src/main/java/` 为空
- [ ] `grep -rn "READ_EXTERNAL_STORAGE\|WRITE_EXTERNAL_STORAGE\|MANAGE_EXTERNAL_STORAGE" app/src/main/` 为空
- [ ] `grep -n "ACTION_OPEN_DOCUMENT_TREE" app/src/main/java/ -r` 为空
- [ ] `grep -n "LOCKED_BOOT_COMPLETED" app/src/main/ -r` 为空
- [ ] `grep -rn "Session" app/src/main/java/com/li63050a/linuxandroid/ | grep -i holder` 为空（旧的会话单例类已删除）

> **再次强调**：本节所有用例在本环境**均未执行**（无 JDK / 无 Android SDK / 无设备，
> 代码未经编译）。这是一份**待执行的测试计划**，不是测试结果。执行后请把实际观察到的
> 现象回填进来，与预期不符的按「先查代码、再改文档」的顺序处理。
>
> **多实例轮（§7.9–§7.14）同样一条都没跑过**：`SessionManager` / `ResourceLimits` /
> `MirrorProbe` / `MultiPartDownloader` / `AptSourceSwitcher` / `InstanceConfig` 全部
> **未经编译**，文中的常量、超时、文件名都是从源码逐字读出来的，但**没有在设备上
> 验证过任何一条**。§7.9–§7.14 与 §8 的 MI/RL/MX/MP/AS/IC 清单均为「要验证 X，应做 Y，
> 预期 Z」的待执行项。
>
> 相关：[ARCHITECTURE.md](ARCHITECTURE.md)（§4.4 起的分层与 §13 风险）、
> [MODULES.md](MODULES.md)（各类 API 契约）、[COMPLIANCE.md](COMPLIANCE.md)（§8.13 / §8.14 / §8.15 / §8.17–§8.21 合规状态）、
> [TROUBLESHOOTING.md](TROUBLESHOOTING.md)（§5.15–§5.21 本节用例失败时的排查入口）。
