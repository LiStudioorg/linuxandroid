# LinuxAndroid / ProotTerm 架构与开发文档

> **一句话定位**：LinuxAndroid（界面品牌名 **ProotTerm**）是一个免 root 的 Android 应用，内置 PRoot（Termux 适配版）二进制，在应用私有目录解压用户选择的 Linux rootfs（Alpine / Debian / Ubuntu），并通过一个简易文本终端驱动其中的 shell。

| 项目 | 值 |
| --- | --- |
| package / applicationId / namespace | `com.li63050a.linuxandroid` |
| versionName / versionCode | `0.0.0.1` / `1` |
| minSdk / targetSdk / compileSdk | `24` / `36` / `36` |
| 支持系统 | Android 7.0（API 24）～ Android 16（API 36） |
| ABI | 仅 `arm64-v8a`（`abiFilters += "arm64-v8a"`） |
| 语言 / UI | Kotlin 100%、XML 布局 + AppCompat + Material Components（**不使用 Compose**） |
| 权限 | 仅 `android.permission.INTERNET`，不申请任何存储权限 |
| 数据位置 | 全部位于应用私有目录 `filesDir` |

配置来源：`app/build.gradle.kts:9-21`（namespace / SDK / versionCode / versionName / abiFilters）、`app/src/main/AndroidManifest.xml:4`（唯一权限）。

**配套文档**：[MODULES.md](MODULES.md)（逐类 API 契约与坑）、[COMPLIANCE.md](COMPLIANCE.md)（AGENTS.md 硬性约束逐条核查）、[AGENTS.md](../AGENTS.md)（编码约定）、[README.md](../README.md)（用户视角）。

---

## 目录

1. [架构总览](#1-架构总览)
2. [启动与生命周期](#2-启动与生命周期)
3. [核心链路 A：下载安装](#3-核心链路-a下载安装)
4. [核心链路 B：解压](#4-核心链路-b解压)
5. [核心链路 C：PRoot 启动](#5-核心链路-cproot-启动)
6. [核心链路 D：终端 UI 交互](#6-核心链路-d终端-ui-交互)
7. [状态机：VersionAdapter.State](#7-状态机versionadapterstate)
8. [数据与文件布局](#8-数据与文件布局)
9. [配置驱动：rootfs_manifest.json](#9-配置驱动rootfs_manifestjson)
10. [minSdk 24 兼容约束](#10-minsdk-24-兼容约束)
11. [关键设计决策表](#11-关键设计决策表)
12. [扩展指南](#12-扩展指南)
13. [会话架构：多实例 + 三层职责分离](#13-会话架构多实例--三层职责分离)
14. [资源限制（ulimit 语义）](#14-资源限制ulimit-语义)
15. [镜像优选与多线程下载](#15-镜像优选与多线程下载)
16. [APT 换源](#16-apt-换源)
17. [存储位置抽象](#17-存储位置抽象)
18. [开机自启动链路](#18-开机自启动链路)
19. [已知问题与风险清单](#19-已知问题与风险清单)
20. [附录：关键常量速查](#20-附录关键常量速查)

---

## 1. 架构总览

### 1.1 分层图

```
+-----------------------------------------------------------------------------------+
|  UI 层（Activity / Adapter / XML 布局，仅做展示与事件转发）                          |
|                                                                                   |
|   MainActivity             LogActivity                                            |
|   DistroAdapter            VersionAdapter                                         |
|   activity_main.xml  view_terminal.xml  view_distros.xml  view_versions.xml       |
|   view_settings.xml  activity_log.xml    item_distro.xml  item_version.xml        |
|   nav_menu.xml       file_paths.xml                                               |
+-----------------------------------------------------------------------------------+
        |  调用（同步方法 / 回调 lambda）              ^  主线程回调（Handler / runOnUiThread）
        v                                             |
+-----------------------------------------------------------------------------------+
|  业务 / 编排层（无 Android UI 依赖，可单元测试）                                     |
|                                                                                   |
|   RootfsManager      目录规划、空间检查、原子安装、卸载              (102 行)        |
|   ManifestLoader     assets JSON 解析 -> List<DistroFamily>          ( 65 行)        |
|   DistroInfo         DistroFamily / DistroInfo 数据模型              ( 43 行)        |
+-----------------------------------------------------------------------------------+
        |  调用                                        ^  异常向上抛（IOException 等）
        v                                             |
+-----------------------------------------------------------------------------------+
|  基础设施层（IO / 网络 / 日志 / 归档）                                              |
|                                                                                   |
|   RootfsDownloader   OkHttp + Range 续传 + 多镜像回退                (180 行)        |
|   RootfsExtractor    tar 解压 + 符号链接 + 权限位 + 防穿越           (208 行)        |
|   Sha256Utils        流式 SHA256                                     ( 30 行)        |
|   AppLogger          内存环形缓冲 + app.log 滚动 + Logcat            (136 行)        |
|   NativeDeps         assets 运行库释放到 filesDir/native             ( 50 行)        |
+-----------------------------------------------------------------------------------+
        |  ProcessBuilder / android.system.Os          ^  InputStream / exit code
        v                                             |
+-----------------------------------------------------------------------------------+
|  原生层（随 APK 打包的 aarch64 二进制与共享库，仅 arm64-v8a）                        |
|                                                                                   |
|   jniLibs/arm64-v8a/libproot.so          <- usr/bin/proot                         |
|   jniLibs/arm64-v8a/libproot-loader.so   <- usr/libexec/proot/loader              |
|   assets/native/arm64-v8a/libtalloc.so.2          proot 的 NEEDED 依赖             |
|   assets/native/arm64-v8a/libandroid-shmem.so     proot 的 NEEDED 依赖             |
|                                                                                   |
|   ProotSession       进程生命周期 + 环境变量拼装 + 输出读取          (201 行)        |
+-----------------------------------------------------------------------------------+
        |
        v
   filesDir/rootfs/<versionId>/  （Alpine / Debian / Ubuntu 的 rootfs 目录树）
```

### 1.2 模块职责表

| 类名 | 文件 | 行数 | 职责 | 关键依赖 |
| --- | --- | ---: | --- | --- |
| `App` | `app/src/main/java/com/li63050a/linuxandroid/App.kt` | 56 | 全局 Application：`AppLogger.init(filesDir)`、安装 `UncaughtExceptionHandler`（先落盘堆栈再杀进程） | `AppLogger`、`android.os.Process` |
| `AppLogger` | `.../AppLogger.kt` | 136 | 三级日志输出：内存环形缓冲（2000 条）+ `filesDir/logs/app.log`（1MB 滚动 `.old`）+ Logcat；线程安全 | `SimpleDateFormat`、`Log` |
| `MainActivity` | `.../MainActivity.kt` | 632 | Drawer 四屏编排（终端 / 发行版 / 版本 / 设置）；安装、启动、卸载、快捷键、返回键逻辑 | 全部业务与基础设施类 |
| `LogActivity` | `.../LogActivity.kt` | 103 | 日志页：刷新 / 清空 / 复制 / 分享；分享必须走 FileProvider `content://` | `AppLogger`、`FileProvider` |
| `DistroInfo` | `.../DistroInfo.kt` | 43 | 数据模型 `DistroFamily`、`DistroInfo`；`DistroInfo.allUrls` 把 `url + mirrors` 展平为候选列表 | 纯 Kotlin |
| `ManifestLoader` | `.../ManifestLoader.kt` | 93 | 解析 `assets/rootfs_manifest.json`（version 2 嵌套结构）为 `List<DistroFamily>` | Android 内置 `org.json` |
| `RootfsManager` | `.../RootfsManager.kt` | 143 | 目录规划、`.installed` 标记、空间检查、原子 rename 安装、卸载清理 | `StatFs`、`File` |
| `RootfsDownloader` | `.../RootfsDownloader.kt` | 180 | OkHttp 下载：`.part` + Range 续传、多镜像回退、206/200/416 分支、200ms 进度节流 | `OkHttpClient`、协程 |
| `RootfsExtractor` | `.../RootfsExtractor.kt` | 208 | tar/tar.gz/tar.xz 解压：符号链接 / 硬链接 / 权限位 / 路径穿越防护，API 24 安全 | Commons Compress、`android.system.Os` |
| `Sha256Utils` | `.../Sha256Utils.kt` | 30 | 128KB 缓冲流式 SHA256；`expected` 为空白视为跳过校验 | `MessageDigest` |
| `NativeDeps` | `.../NativeDeps.kt` | 50 | 首启释放 `libtalloc.so.2`、`libandroid-shmem.so` 到 `filesDir/native`，`chmod +x` | `assets.open`、`FileOutputStream` |
| `ProotSession` | `.../ProotSession.kt` | 201 | 持久 shell 进程：命令行拼装、`PROOT_TMP_DIR`/`PROOT_LOADER`/`LD_LIBRARY_PATH` 注入、后台读线程、`sendCommand`/`sendRaw`/`destroy` | `ProcessBuilder`、`Handler(Looper.getMainLooper())` |
| `DistroAdapter` | `.../DistroAdapter.kt` | 46 | 家族卡片 RecyclerView 适配器（3 项，点击进入版本页） | `item_distro.xml` |
| `VersionAdapter` | `.../VersionAdapter.kt` | 160 | 版本列表适配器；`State` 状态机 + `PAYLOAD_PROGRESS` 局部刷新 | `item_version.xml` |

> **总计**：14 个 Kotlin 文件、2081 行。

---

## 2. 启动与生命周期

### 2.1 App.onCreate（进程级）

`App.kt:14-19`：

1. `super.onCreate()`
2. `AppLogger.init(filesDir)` —— 创建 `filesDir/logs/`，把 `logFile` 指向 `app.log`（`AppLogger.kt:28-39`）
3. `installUncaughtHandler()` —— 包装默认 `UncaughtExceptionHandler`（`App.kt:21-51`）
4. `AppLogger.i(TAG, "App started, sdk=${Build.VERSION.SDK_INT}")`

异常处理器行为（`App.kt:23-50`）：

```
Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
  try   { AppLogger.e("App", "Uncaught exception on thread ${thread.name}", throwable) }
  catch { /* 日志失败也不能挡在杀进程前 */ }
  finally {
      Thread.sleep(200)                 // 给文件写入留时间
      default?.uncaughtException(...)   // 交回系统默认处理器
        ?: run { Process.killProcess(Process.myPid()); exit(10) }
  }
}
```

### 2.2 MainActivity.onCreate 初始化顺序

`MainActivity.kt:84-128`，严格顺序如下：

```
MainActivity.onCreate(savedInstanceState)
  │
  ├─ 1. super.onCreate + setContentView(R.layout.activity_main)
  │
  ├─ 2. manager = RootfsManager(this)                    :88
  │        └─ filesDir / rootfsRoot 就绪
  │
  ├─ 3. findViewById × 16                                :90-105
  │        toolbar, drawer, navView,
  │        viewTerminal, viewDistros, viewVersions, viewSettings,
  │        editSearch, tvOutput, scrollOutput, editCommand, btnSend,
  │        recyclerDistros, recyclerVersions, tvVersionsHeader, btnCtrl
  │
  ├─ 4. families = ManifestLoader.load(this)             :107-113
  │        └─ 异常分支: AppLogger.e + Toast「清单解析失败」+ families = emptyList()
  │
  ├─ 5. distroAdapter = DistroAdapter(families) { openVersions(it) }   :115
  │        recyclerDistros.layoutManager = LinearLayoutManager
  │        recyclerDistros.adapter = distroAdapter
  │
  ├─ 6. setupVersionList()   :130  -> versionAdapter（回调 install/launchVersion/confirmUninstall）
  ├─ 7. setupNavigation()    :141  -> 汉堡开抽屉 + NavigationView 四项分发
  ├─ 8. setupTerminal()      :162  -> 发送按钮 / IME_ACTION_SEND / 搜索框 TextWatcher
  ├─ 9. setupHotkeys()       :182  -> ESC / CTRL / TAB / 方向键 / 字母键动态查找
  ├─ 10. setupSettings()     :214  -> 版本、包名、ABI、SDK、rootfs 路径、GitHub、清空终端
  │
  ├─ 11. showScreen(Screen.DISTROS)                      :125   ← 默认落在「发行版管理」
  ├─ 12. toolbar.subtitle = getString(R.string.status_idle)     :126
  └─ 13. AppLogger.i("MainActivity", "onCreate families=${families.size}")  :127
```

**注意**：`families` 解析失败不会崩溃；`distroAdapter` 会基于空列表构造，界面显示空列表。

### 2.3 MainScope 生命周期与 onDestroy 清理链

`MainActivity.kt:43` 声明 `private val scope = MainScope()`；所有安装任务通过 `scope.launch { ... }`（`:320`）启动，天然绑定主线程调度器。

`MainActivity.kt:578-587` 的清理链：

```kotlin
override fun onDestroy() {
    installJob?.let {
        it.cancel()          // 1. 取消安装协程 Job
        downloader.cancel()   // 2. 取消活动 OkHttp Call
        multiDownloader.cancel()  // 3. 取消分块下载（并清分块临时文件）
    }
    scope.cancel()            // 4. 取消整个 MainScope
    // ⚠️ 这里**没有** session.destroy() —— 会话不归 Activity 所有（§8.13）
    super.onDestroy()
}
```

> ⚠️ **这个方法是 §8.13 的核心**：早期版本在 `onDestroy` 里调 `session.destroy()`，导致「后台保持运行」与「开机自启动」双双失效。现在销毁 Activity **绝不**影响任何运行中的会话；唯一的权威结束点是 `ACTION_STOP`。

清理顺序的语义：

| 步骤 | 方法 | 效果 |
| --- | --- | --- |
| 1 | `installJob.cancel()` | 停止安装协程（解压循环内的 `ensureActive()` 会抛 `CancellationException`） |
| 2 | `downloader.cancel()` | 结束活动 OkHttp Call，`.part` 保留供续传 |
| 3 | `multiDownloader.cancel()` | 置取消标志，让分块循环退出并清理分块临时文件 |
| 4 | `scope.cancel()` | 取消整个 `MainScope`，防止泄漏的协程继续回调已销毁的视图 |

> 顺序有讲究：先取消「会产生后续副作用」的任务（安装、下载），最后才取消作用域。反过来先 `scope.cancel()` 虽然也能停，但 `installJob` 的 `finally` 清理块可能来不及跑，留下半成品 tmp 目录。


---

## 3. 核心链路 A：下载安装

### 3.1 一次安装的完整时序

```
用户点「下载」（VersionAdapter / MyAppAdapter 都汇到 MainActivity.install(d)）
  │
  ├─ 0. 闸门：activeInstallId != null → 直接 Toast 拒绝（同时只允许一个安装任务）
  │
  ├─ 1. 读实例配置：InstanceStore.of(this).get(d.id)
  │       └─ 得到 ResourceLimits / mirrorOverride / preferAutoMirror / downloadThreads
  │
  ├─ 2. 组装候选镜像列表（§15.1 三层）
  │       customMirror（可选） + d.allUrls（url + mirrors[]，去重）
  │       └─ 若 preferAutoMirror && size > 1 → MirrorProbeService.rank(candidates)
  │
  ├─ 3. 空间检查：RootfsManager.usableSpace() 与 d.size 比对（检查**目标位置**分区）
  │
  ├─ 4. 下载（§15.2）
  │       total 已知 && threads > 1 && total >= 8MB → MultiPartDownloader 分块并发
  │       否则 → RootfsDownloader 单线程 Range 断点续传
  │       产出：<sandbox>/<id>.<format>.part
  │
  ├─ 5. SHA256 校验（仅当清单 sha256 非空）
  │
  ├─ 6. 解压到 <sandbox>/tmp_<id>（§4）
  │
  ├─ 7. RootfsManager.finalizeInstall()
  │       旧 rootfs/<id> → rename 成 rootfs/<id>.old（回滚点）
  │       tmp_<id> → rename 成 rootfs/<id>
  │       任一步失败：把 <id>.old 挪回原位，旧安装不丢
  │
  ├─ 8. 写 .installed 标记 + .shell 标记（记录清单声明的 shell）
  │      删除 .old，删除 .part 与全部分块
  │
  └─ 9. VersionAdapter 刷新为「启动」
```

**为什么第 7 步要先备份再 rename**：解压几十万个文件的 rootfs 无法「原子替换」。先把旧目录改名（rename 在同一文件系统内是原子的、几乎瞬时），再把新目录改名到最终位置。这样任何时刻磁盘上要么是完整的旧版本、要么是完整的新版本，不存在「一半新一半旧」。失败时把 `.old` 改回来即可，用户不会因为一次失败的升级丢掉能用的系统。

### 3.2 关键点：temp 与 dest 必须同分区

`renameTo` 跨文件系统会失败。`RootfsManager` 的 `tempDir` / `rootfsDir` / `partFile` 三个路径**全部从同一个 `sandboxDir` 推导**，因此天然同分区。这是「存储位置可选」（§17）能成立的前提——如果 tmp 固定内部而 dest 在外部，rename 就会失败。

### 3.3 取消语义

取消分三种情况，都必须在 `Dispatchers.IO` 中清理临时文件：

| 时机 | 清理内容 |
| --- | --- |
| 下载中取消 | `downloader.cancel()` + `multiDownloader.cancel()`（后者负责删分块），`.part` **保留**供续传 |
| 下载失败 | 剥掉失效候选后重试；全失败则保留 `.part` 并恢复按钮为「下载」 |
| 卸载 | `deletePartFiles(id)`（匹配 `.part` 子串，因此 `.part.<N>` 与 `.part.<N>.src` 都被删）+ `cleanupChunksFor(manager, id)` |

`deletePartFiles` 用的是 `contains(".part")` 而非 `endsWith(".part")`——后者会漏掉分块文件，这是本轮为支持多线程下载而改的。

---

## 4. 核心链路 B：解压

### 4.1 为什么不用现成库解 tar

Android 没有内置 tar 解压（更别说 xz）。项目用 Apache Commons Compress 的 `TarArchiveInputStream` 套在 `GzipCompressorInputStream`（tar.gz）或 `XZCompressorInputStream`（tar.xz）外面流式解析。termux 的 proot-distro 归档是 tar.xz，官方镜像多为 tar.gz，两种都要支持。

### 4.2 三个必须自己处理的问题

**① 路径穿越**：恶意或损坏的归档可含 `../../` 条目。做法是去前导 `/` 后拼接，再要求 `canonicalPath` 等于 `dest` 或以 `dest + File.separator` 开头，否则拒绝。

**② 符号链接与权限位**：rootfs 里大量使用符号链接（如 `/bin/sh → dash`、`/lib64 → /lib`）和 setuid/可执行位。Java 的 `File` API 无法创建符号链接或设权限，必须用 `android.system.Os.symlink` / `Os.chmod`。权限位取 `entry.mode and MODE_MASK(0x1FF)`（即 `0777`）。**mode 为 0 时跳过 chmod**——某些归档不记录权限，`chmod 0` 会让文件彻底不可读。

**③ 删除已存在路径不能跟随链接**：覆盖安装时目标可能是符号链接。`File.delete()` 语义不清且可能跟随链接删到链接目标，因此用 `Os.lstat`（不跟随）+ `Os.remove`。

### 4.3 硬链接：为什么要多轮重试

tar 里的硬链接条目可能**先于**它指向的目标文件出现（取决于打包顺序）。单遍处理必然遇到「目标还不存在」。做法是主循环把硬链接条目收集到列表，主循环结束后多轮重试，直到某一轮没有任何进展为止。逻辑上必然收敛（每轮至少成功一个，否则退出），但极端情况下最坏是 O(n²) 次存在性探测。

### 4.4 目录权限延后恢复

解压期间如果立刻把目录设成归档里的权限（可能是 `0555`），后续写入子文件就会失败。因此解压期间目录保持默认可写，全部结束后**逆序**（先深后浅）统一 `chmod` 还原。

### 4.5 API 24 约束

`java.nio.file` 在 API 26 才有，因此 `Files.delete` / `toPath()` 一律禁用（§8.6）。这是硬约束，全仓 grep 必须为空。

---

## 5. 核心链路 C：PRoot 启动

### 5.1 命令行

```
<nativeLibraryDir>/libproot.so
  -r <rootfs>           # 把该目录当根
  -0                    # 伪造 root（uid 0），否则 guest 内处处 permission denied
  -w /root              # 初始工作目录
  -b /dev               # 绑定挂载：让 guest 看到真实设备节点
  -b /proc              # 进程信息（ps/top 需要）
  -b /sys               # 部分工具需要
  -b /dev/urandom:/dev/random   # Android 无 /dev/random，用 urandom 顶上
  <guest 命令 argv>     # 见 §5.2
```

### 5.2 最后一个参数不是「一段脚本」，而是 argv 序列

> ⚠️ **这是本项目最容易致命的一个误解。**

proot **不会**替你调用 shell。它把命令行里第一个非选项参数当作**可执行文件路径**解析（`src/path/path.c` 的 `which()`：字符串含 `/` 时按显式路径 `realpath`），然后 `launch_process()` 直接 `execvp(tracee->exe, argv)`——**全程没有 `sh -c`**。

因此这两种写法完全不同：

```kotlin
// ✅ 正确：3 个独立 argv，由 /bin/sh 自己解析 -c 后面的脚本
listOf("/bin/sh", "-c", "ulimit -v 524288; exec \"/bin/bash\"")

// ❌ 错误：整段脚本是 1 个 argv，proot 会去找一个叫
//    `ulimit -v 524288; exec "/bin/bash"` 的文件
listOf("ulimit -v 524288; exec \"/bin/bash\"")
```

错误写法的报错是 `'ulimit ...' not found (root = ..., $PATH=...)`。**已在本仓 `libproot.so` 中确认该格式串存在，且二进制内不含 `-c` 字符串**——即 proot 侧不存在任何 shell 包装逻辑。

`ResourceLimits.toGuestCommand(shell)` 封装了正确写法；无限制时返回 `listOf(shell)`。

### 5.3 环境变量（全部绝对路径，§8.8）

| 变量 | 值 | 为什么 |
| --- | --- | --- |
| `PROOT_TMP_DIR` | `filesDir/proot_tmp` | proot 在此创建临时文件；**固定内部**（§8.14），因为外部位置在 Android 11+ 上通常不可执行 |
| `PROOT_LOADER` | `nativeLibraryDir/libproot-loader.so` | proot 的 loader 在 Android 上必须改名成 `lib*.so` 放进 `jniLibs` 才会被解压出来 |
| `LD_LIBRARY_PATH` | `filesDir/native` | proot 依赖 `libtalloc.so.2` / `libandroid-shmem.so`；proot 内置 RUNPATH 指向 Termux 私有目录，其他设备上不存在，必须靠 `NativeDeps` 释放后指过去 |

guest 侧环境：`TERM=xterm-256color`、`HOME=/root`、`LANG=C.UTF-8`、`TMPDIR=/tmp`、guest `PATH`、`PROOTTERM_VERSION=<versionId>`（供用户脚本判断当前系统）。

### 5.4 为什么必须「先进前台再 fork」

Android 8+ 要求 `startForegroundService()` 之后 5 秒内调用 `startForeground()`，否则 ANR。而准备 rootfs（校验、释放 native 库、组命令）可能更慢。因此 `ProotService` 先无条件 `startForegroundCompat(...)` 把通知挂出去，再进 `SessionManager.startBlocking(...)`。

### 5.5 输出读取

`redirectErrorStream(true)` 把 stderr 并进 stdout（用户要看到报错），后台线程用 `InputStreamReader(UTF_8)` 读，`mainHandler.post` 回主线程追加到缓冲。

**输出不写 AppLogger**——交互式命令几分钟就能刷满 1MB 环形缓冲、把启动失败信息挤掉。只记启动命令、环境变量、退出码。

### 5.6 非 PTY 的代价

没有 PTY 就没有提示符、没有回显、`Ctrl-C` 不会产生 `SIGINT`。这是设计取舍（PTY 需要 `forkpty` + JNI，复杂度陡增）。缓解手段：UI 本地回显 `$ <cmd>`，README 建议用 `sh -i`。

### 5.7 外部存储的执行限制

`/Android/data` 在 Android 11+ 由 FUSE 提供，通常不允许执行文件，proot 会报 permission denied。`ProotSession.explainStartFailure()` 把这类错误翻译成「请把存储位置改为内部存储后重新安装」。这是平台限制，不是 bug（§17、§19）。

---

## 6. 核心链路 D：终端 UI 交互

### 6.1 输出渲染

两层缓冲：`ProotSession` 内一份（`MAX_OUTPUT_CHARS = 200_000`），`MainActivity` 的 `outputRaw` 一份。UI 层渲染时叠加搜索框过滤，超限时丢头部一半并在顶部提示「已截断 N 字符」。自动滚到底部。

### 6.2 发送命令的两条路径

| 方法 | 用途 | 实现 |
| --- | --- | --- |
| `SessionManager.sendCommand(id, cmd)` | 普通命令 | 追加 `\n` 后写 stdin |
| `SessionManager.sendRaw(id, data)` | 控制序列 | 原样写 stdin，**不追加换行** |

两者都要求实例正在运行，否则抛 `IOException("实例 $id 没有运行中的会话")`。

### 6.3 快捷键：必须发真实控制序列（§8.16）

| 键 | 序列 |
| --- | --- |
| ESC | `\u001b` |
| TAB | `\u0009` |
| 方向键 | `\u001b[A` / `[B` / `[C` / `[D` |
| HOME / END | `\u001b[H` / `\u001b[F` |
| PGUP / PGDN | `\u001b[5~` / `\u001b[6~` |
| CTRL + 字母 | `(c - 'a' + 1)` → `0x01..0x1A` |
| ALT + 字母 | `"\u001b" + c` |

禁止做成空壳按钮。CTRL/ALT 按下后进入「待命」状态，展开 a~z 组合键行；再按一次取消。

### 6.4 安全键盘规避（§8.15）

某些输入法（尤其国产 ROM）对「密码类」输入框会切到安全键盘：无联想、无粘贴，还可能截屏保护。做法是让输入框「看起来像可见密码」而不是「像搜索框」：

- `inputType = textVisiblePassword | textNoSuggestions`（**不是** `textPassword`）
- `imeOptions = actionNone | flagNoExtractUi | flagNoFullscreen`（**不是** `actionSend`/`actionSearch`，全屏提取 UI 会盖住终端）
- `importantForAutofill = no`

集中定义在 `themes.xml` 的 `App.TerminalInput`，运行期再由 `MainActivity.hardenImeForTerminal()` 覆盖一次（防主题被别的 style 覆盖）。

---

## 7. 状态机：VersionAdapter.State

```
                    ┌──────────┐
        ┌──────────►│   Idle   │◄──────────┐
        │           └────┬─────┘           │
        │                │ 点「下载」        │ 取消/失败
        │           ┌────▼─────────┐       │
        │           │ Downloading  │───────┘
        │           └────┬─────────┘
        │                │ 用户点「取消」
        │           ┌────▼─────────┐
        │           │ Cancelling   │
        │           └────┬─────────┘
        │                │
        │ 点「卸载」  ┌────▼─────────┐
        └────────────│  Installed   │
                     └─────────────┘
                     ┌────────────────────┐
                     │ InstalledElsewhere │  ← 装在**另一个**存储位置
                     └────────────────────┘
```

`InstalledElsewhere` 是本项目特有的状态：rootfs 装在另一个存储位置。按钮显示「重新下载」而非「启动」——后者会立刻报「尚未安装」，用户无法理解。这个状态由 `RootfsManager.isInstalledElsewhere()` 判定，是「存储位置可切换」（§17）的必要配套。

进度更新走 `payload` 局部刷新（`PAYLOAD_PROGRESS`），避免整行重建导致闪烁。

---

## 8. 数据与文件布局

### 8.1 内部位置（默认）

```
filesDir/                          # /data/data/<pkg>
  rootfs/<id>/                     # 最终 rootfs（含 .installed / .shell）
  rootfs/<id>.old/                 # 升级安装的回滚点，成功后删除
  tmp_<id>/                        # 解压临时目录
  <id>.<format>.part               # 单线程断点续传缓存
  <id>.<format>.part.<N>           # 多线程分块（N = 0..7）
  <id>.<format>.part.<N>.src       # 分块来源标记（内容 "url|total"）
  <id>.<format>.part.merge         # 合并中间文件，校验通过后改名
  instances.json                   # 每实例配置（§14/§15/§16）
  native/                          # NativeDeps 释放的运行库【固定内部】
  proot_tmp/                       # PROOT_TMP_DIR【固定内部】
  logs/                            # AppLogger【固定内部】
```

### 8.2 外部位置

`getExternalFilesDir(null)` = `/Android/data/<pkg>/files`，布局与内部同构，但**只有 rootfs 相关部分会用到**。

### 8.3 什么随位置变，什么不变

| 路径 | 是否跟随存储位置 | 原因 |
| --- | --- | --- |
| `rootfs/<id>/`、`rootfs/<id>.old/`、`tmp_<id>/`、`*.part*` | ✅ 跟随 | 这些是用户可见的「数据」，放外部可省内部空间 |
| `native/` | ❌ 固定内部 | 必须以可执行方式加载 |
| `proot_tmp/` | ❌ 固定内部 | proot 需要在此执行临时文件；外部在 Android 11+ 上通常 noexec（§8.14） |
| `logs/` | ❌ 固定内部 | 排查存储问题时日志必须还在可靠位置 |
| `instances.json` | ❌ 固定内部 | 配置必须始终可读，不能因外部未挂载而「全部实例配置消失」 |

这条区分是 §8.14 的核心：**「切换存储位置」实际只改变 `rootfs/`、`tmp_*`、`*.part*` 三类路径的落点。**

---

## 9. 配置驱动：rootfs_manifest.json

### 9.1 结构

```json
{
  "version": 2,
  "distros": [
    {
      "id": "alpine", "name": "Alpine Linux", "description": "...", "icon": "...",
      "versions": [
        {
          "id": "alpine-3.20", "name": "Alpine 3.20",
          "size": 3565158,
          "url": "https://...",
          "mirrors": ["https://..."],
          "sha256": "abc...",
          "format": "tar.gz",
          "defaultShell": "/bin/sh"
        }
      ]
    }
  ]
}
```

版本 `id` 直接用作 rootfs 目录名，因此**必须全局唯一且不含路径分隔符**。

### 9.2 校验

`ManifestLoader` 做三件事：

1. 顶层 `version` 必须等于 `SUPPORTED_VERSION`（当前 `2`），否则抛 `IllegalArgumentException`。**改结构必须同步提升该常量**，否则老版本 APK 会静默按新结构解析出错。
2. 每个版本的 `format` 必须在 `RootfsExtractor` 支持集合（`tar.gz` / `tar.xz`）内。
3. 空清单直接拒绝（避免 UI 显示空白却无解释）。

### 9.3 新增发行版的正确做法（§8.10）

只改 `rootfs_manifest.json`。**不要**动下载/解压/启动逻辑——那些是格式驱动的，与具体发行版无关。唯一需要注意 distro 特有差异的地方是 APT 换源（§16），那里按 `os-release` 的 `ID` 分派，也已数据驱动。

### 9.4 当前清单内容

| 家族 | 版本 | 格式 | sha256 |
| --- | --- | --- | --- |
| Alpine | 3.20 / 3.19 / 3.18 | tar.gz | ✅ 有 |
| Debian | 13 / 12 / 11 | tar.xz | ❌ **空** |
| Ubuntu | 24.04.5 / 22.04.5 / 20.04.5 | tar.gz | ✅ 有 |

> ⚠️ Debian 三个版本没有 sha256，因此**没有完整性校验**，仅依赖 HTTP `Content-Length` 比对。这是风险清单第 2 条，也是 §15 里分块复用必须绑定来源标记的直接原因。

---

## 10. minSdk 24 兼容约束

### 10.1 必须分支保护的高版本 API

| API | 起始版本 | 项目内替代/分支 |
| --- | --- | --- |
| `Process.isAlive` | 26 | `exitValue()` + `catch (IllegalThreadStateException)` |
| `Process.waitFor(long, TimeUnit)` | 26 | `waitFor()`（阻塞，放在后台线程） |
| `Process.destroyForcibly` | 26 | `destroy()` |
| `NotificationChannel` / `getNotificationChannel` | 26 | SDK_INT 判断后才创建/查询 channel |
| `startForeground(int, Notification, int)` | 29 | `startForegroundCompat()` 内分支 |
| `View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS` | 26 | `hardenImeForTerminal()` 内分支 |
| `java.nio.file.*` | 26 | **禁止使用**，改用 `Os.lstat` / `Os.remove` |

### 10.2 已确认 API 24 可用（无需分支）

曾担心但查证后确认可用的：

- `Environment.getExternalStorageState(File)` / `isExternalStorageEmulated(File)`（API 21+）
- `OsConstants.S_IFMT` / `S_IFLNK`、`Os.lstat`（API 21+）
- `Service.stopForeground(int)`（API 24+）
- `PendingIntent.FLAG_IMMUTABLE`（API 23+）
- `MaterialSwitch`（Material Components 1.7.0+，本项目 1.12.0）

`android.system.Os.*`（symlink / link / chmod / lstat / remove）自 API 21 可用。

---

## 11. 关键设计决策表

| 决策 | 选择了 | 放弃了 | 理由 |
| --- | --- | --- | --- |
| 运行时 | PRoot | chroot / 真 root / 虚拟机 | 免 root 是产品前提；chroot 需 root，虚拟机太重 |
| 终端 | 纯管道（无 PTY） | `forkpty` + JNI | PTY 复杂度高；纯管道已能跑命令，代价是无提示符/无 Ctrl-C |
| 会话归属 | 前台服务 + 进程内注册表 | Activity 持有 / 全局单例 | Activity 会被销毁；单例无法支持多实例（§13） |
| 多实例 | 每实例一个 `SessionSlot` | 全局「当前会话」 | 用户明确要求同时运行多个系统（§8.17） |
| 资源限制 | `ulimit` | cgroup | 非 root Android 拿不到 cgroup 写权限（§14.1） |
| 存储位置 | 两个应用私有沙箱 | SAF / `MANAGE_EXTERNAL_STORAGE` | 私有沙箱无需任何权限（§8.2） |
| 换源备份 | 一次性备份 + 原子写 | 每次换源都备份 | 反复换源会污染备份链，导致无法恢复原始源（§16.4） |
| 分块复用 | 长度 + 来源标记 | 仅长度 | 镜像顺序不稳定，仅凭长度会拼出损坏归档（§15.3） |

---

## 12. 扩展指南

| 我想…… | 改哪里 | 注意 |
| --- | --- | --- |
| 新增发行版/版本 | `assets/rootfs_manifest.json` | 只改这一个文件（§8.10） |
| 新增镜像加速站 | `AptSourceSwitcher.ALL` | 必须同时提供 `debianBase` 与 `alpineBase`；arm64 路径见 §16.2 |
| 改终端外观 | `res/values/colors.xml` + `view_terminal.xml` | 全 vector，不引入 PNG |
| 加一个终端快捷键 | `MainActivity` 的 `KEY_*` + `view_terminal.xml` | 必须发真实控制序列（§8.16） |
| 加一类资源限制 | `ResourceLimits`（字段 + `toScript()` + `summary()` + `toJson()`/`fromJson`） | 别忘了 UI 的 `dialog_instance_settings.xml` 与 `strings.xml` |
| 加一种归档格式 | `RootfsExtractor` 的格式集合 + 清单 `format` | 同时更新 `ManifestLoader` 的校验集合 |
| 加一个实例级配置项 | `InstanceConfig` + `InstanceStore.of()` 读写 + 实例设置对话框 | **不得**塞进 `AppPrefs`，那是全局偏好（§8.17） |
| 调整分块下载策略 | `MultiPartDownloader` 的 `MAX_THREADS` / `MIN_MULTIPART_BYTES` | 改分块命名必须同步改 `cleanupChunks` 与 `deletePartFiles` |
---

## 13. 会话架构：多实例 + 三层职责分离

### 13.1 三层

| 组件 | 负责 | 不负责 |
| --- | --- | --- |
| `ProotSession` | 单个进程 + 输出缓冲（主线程限定）、发命令、destroy | 不知道 Android 生命周期 |
| `SessionManager` | 进程内**多会话**注册表：状态机、代次、观察者转发 | 不启动服务、不建通知 |
| `ProotService` | 前台服务：保活、常驻通知、承载启动流程 | 不直接持有 `ProotSession` |

### 13.2 为什么 Activity 不能持有 shell（§8.13）

Activity 会因旋转、内存回收、用户返回而被销毁。如果 shell 归它所有，上述任一情况都会杀掉用户正在跑的任务——「后台保持运行」和「开机自启动」都无从谈起。因此 shell 归前台服务所有，Activity 只是**观察者**（`attach` / `detach`）。

### 13.3 从单例到多实例：为什么旧设计必须重写

早期版本用 `SessionHolder` 单例持有「当前会话」。它有三个硬伤，在「多实例并存」需求下全部致命：

| 问题 | 后果 |
| --- | --- |
| 单例只存**一个** `ProotSession` 字段 | 启动 B 会覆盖 A 的引用，A 的进程变成无人引用的孤儿——既停不掉也收不到退出事件 |
| 单例只有**一个**代次计数器 | A 的取消操作会误判成 B 的代次变化，B 被错误地判定为「已取消」而拒绝启动 |
| 语义上只有一个「当前会话」 | 无法表达「A 在跑、B 也在跑」，UI 无从区分输出属于谁 |

因此 `SessionHolder.kt` 被**删除**，换成 `SessionManager`（`Map<id, SessionSlot>`）。全仓不再有任何单例式会话访问器。

### 13.4 数据模型

```kotlin
object SessionManager {
    interface Observer {
        fun onSessionState(id: String, state: State, versionName: String?)
        fun onOutput(id: String, text: String)
        fun onExit(id: String, code: Int, reason: String)
    }
    enum class State { IDLE, STARTING, RUNNING, STOPPING }

    private class SessionSlot(val id: String) {
        val epoch = AtomicInteger(0)        // ← 每个实例**独立**的代次
        @Volatile var state = State.IDLE
        @Volatile var session: ProotSession? = null
        @Volatile var versionName: String? = null
        @Volatile var lastError: String? = null
    }
    private val slots = ConcurrentHashMap<String, SessionSlot>()
}
```

**实例 id 就是 rootfs 目录名**（`DistroInfo.id`）。这样 id 天然唯一、稳定，且与磁盘布局一一对应，无需额外的映射表。

### 13.5 代次校验：防止「取消后又被复活」

场景：用户点启动 → fork 进行中（可能耗时数百毫秒）→ 用户等不及点了「结束」。如果不管，fork 完成后会话照样建立，用户会觉得「结束按钮没用」。

做法是 `prepare()` 和 `stop()` 各自把**该实例槽的** `epoch` 自增；`startBlocking()` 在 fork 前后各读一次自己的 epoch，不一致就销毁刚建好的进程并返回 `ABORTED`。

```kotlin
suspend fun startBlocking(context, manager, distro, limits): String? {
    val myEpoch = slot(distro.id).epoch.get()
    if (slot(distro.id).epoch.get() != myEpoch) return ABORTED   // fork 前
    val err = session.start()
    if (slot(distro.id).epoch.get() != myEpoch) {                // fork 后
        session.destroy()
        return ABORTED
    }
    return err
}
```

`epoch` 用 `AtomicInteger` 而非 `@Volatile Int`：`++` 是读-改-写三步，多线程下会丢更新，反而让守卫失效。

### 13.6 返回值三分法

`startBlocking` 的返回值有**三种**含义，调用方必须按顺序判断：

| 返回 | 含义 | 调用方该做什么 |
| --- | --- | --- |
| `null` | 成功 | 可以 `watchSessionEnd()`、写 `lastVersionId` |
| `SessionManager.ABORTED` | 被代次校验中止，**什么都没启动** | 释放服务（若已无实例），**不得**当作成功 |
| 其他字符串 | 失败原因 | 显示在通知里，释放服务 |

> ⚠️ 把 `ABORTED` 当成成功会让服务空转（以为有会话在跑而不敢停）；把它当成「失败」会弹无意义的错误。哨兵值是 `"\u0000aborted\u0000"`，含 NUL，不可能与真实错误信息碰撞。

### 13.7 服务生命周期

- `startJobs: ConcurrentHashMap<String, Job>` 按实例跟踪启动协程。
- 用 `CoroutineStart.LAZY` 创建 job，**先注册再 `job.start()`**——否则协程体可能在 `val job = ...` 赋值完成前就跑到引用 `job` 的那一行。
- `job.invokeOnCompletion { startJobs.remove(id, job) }` 用**两参** remove：若旧 job 在新 job 注册之后才回调，单参 remove 会把新 job 的记录删掉。
- `releaseIfIdle()`：只有当**所有**实例都停了才 `stopSelf()`。
- `watchSessionEnd()`：`while (SessionManager.hasAnyRunning())` 轮询（`WATCH_INTERVAL_MS = 1500`），全部结束才停服务。
- `ACTION_STOP` 可带 `EXTRA_INSTANCE_ID`：带则只停那个实例；不带则停全部。

### 13.8 焦点（`focusedId`）不是所有权

`MainActivity.focusedId` 表示**终端页正在显示哪个实例**。它纯粹是视图状态：

- 改它**绝不**能启动或结束任何进程；
- 所有 `SessionManager.Observer` 回调都必须按 id 过滤，只渲染焦点实例的输出。

> ⚠️ 不过滤是**最容易引入**的多实例 bug：后台实例的输出会串到当前终端里，表现为「终端莫名出现别人的报错」。`onOutput` / `onSessionState` / `onExit` 三处都要判断 `id == focusedId`，非焦点的走日志分支。

### 13.9 已知取舍：服务被回收后通知丢失

若系统在会话存活时回收了前台服务，`START_STICKY` 会以空 intent 重建它，走到 `else ->` 分支后 `stopSelfSafely()`；此时应用无法为仍在运行的 shell 重新挂通知。应用内「结束」仍可用。

这是 §8.13 的固有取舍：**宁可短暂缺通知，也不能因服务被回收就杀掉用户的任务。**

---

## 14. 资源限制（ulimit 语义）

### 14.1 为什么不用 cgroup

cgroup v2 的限制需要写 `/sys/fs/cgroup/**`，非 root 应用没有权限，也没有可用的 Android API。因此在免 root 前提下，唯一能真正下发给 guest 的手段是 **`ulimit`**（即 `setrlimit`）。

代价要说清楚：`ulimit` 是**进程级、继承式**的，只能限制「这个进程树自己」，无法统计整棵树的真实物理内存；而且必须在**启动 shell 时**设置，运行中改不了。

### 14.2 五项限制

| 项 | 对应 | 单位 | 陷阱 |
| --- | --- | --- | --- |
| 虚拟内存 | `ulimit -v` | **KB**（配置是 MB → ×1024） | 限的是**地址空间**，不是物理内存（§19 第 21 条）。设太小（如 64MB）会让动态链接器映射失败，shell **直接起不来** |
| 栈 | `ulimit -s` | **KB**（×1024） | 太小会导致深层递归/大局部变量崩溃 |
| 进程数 | `ulimit -u` | 个 | 同时限制线程；某些程序一启动就要几十个线程，设太小会 fork 失败 |
| 文件描述符 | `ulimit -n` | 个 | 太小会让网络程序报 `Too many open files` |
| CPU 时间 | `ulimit -t` | **秒** | 限的是**累计 CPU 时间**，不是「用几个核」，也不是限速。UI 文案必须如实说明 |

**取值为 0 表示不限制**，此时不生成对应语句（§8.18）。五项全为 0 → `toScript()` 返回 `null` → 走不带包装的启动路径。

### 14.3 `exec` 为什么不可省略

生成的脚本形如：

```sh
ulimit -v 524288; ulimit -u 128; exec "/bin/bash"
```

末尾的 `exec` 让**真正的 shell 替换掉** `sh -c` 这个包装进程。去掉 `exec` 的进程树是：

```
proot ──► sh -c ──► bash(真 shell)          ← 去掉 exec
proot ──► bash(真 shell)                    ← 有 exec
```

`ProotSession.destroy()` 只能杀掉 proot 直接派生的那个子进程。没有 `exec` 时它杀的是 `sh -c`，真正的 `bash` 变成孤儿**继续持有 rootfs 目录**，表现为「结束会话后卸载系统仍提示正在使用」。这是 §8.18 明文要求 `exec` 的原因。

### 14.4 生效时机

改动**只影响新建会话**。已运行的 shell 的 rlimit 在 fork 时就固定了，无法从外部修改。因此 UI 在保存后提示「重启该实例的会话后生效」。

### 14.5 与多实例的关系

每个实例的 `ResourceLimits` 存在它自己的 `InstanceConfig` 里，互不影响。这是「多实例隔离」的一环（§8.17）：给 Alpine 限 256MB 不会影响同时运行的 Debian。

---

## 15. 镜像优选与多线程下载

### 15.1 三层候选来源

```
① 自定义镜像源（InstanceConfig.mirrorOverride，可选，仅 http/https）
② 清单主地址 url
③ 清单备用镜像 mirrors[]
        ↓ 去重
   候选列表 candidates
        ↓ 若 preferAutoMirror && candidates.size > 1
   MirrorProbeService.rank(candidates)
        ↓
   实际下载顺序
```

### 15.2 并发测速

`MirrorProbeService.probe(urls)` 用 `async` 并发探测，**不是**串行等待：

- 单镜像超时 `PER_MIRROR_TIMEOUT_MS = 4_000`
- 整体超时 `TOTAL_TIMEOUT_MS = 6_000`（`withTimeoutOrNull` 包住全部）
- 先 `HEAD`；失败或返回非 2xx/3xx 时退回 `GET` + `Range: bytes=0-0`

> ⚠️ **全部探测失败必须回退清单原顺序**（`rank()` 原样返回候选）。测速只是优化，绝不能因为「测速没结果」而导致无法下载（§8.20）。

回退的 GET 分支**必须显式关闭 body**（`resp.body?.close()`）：OkHttp 只有读完或关闭才把连接归还连接池，只看 `code` 就走会一直占着连接和调度槽，反复测速会累积。

### 15.3 多线程分块下载

触发条件（全部满足才分块）：总长度已知 && `threads > 1` && `total >= MIN_MULTIPART_BYTES(8MB)`。

| 环节 | 做法 |
| --- | --- |
| 切分 | `chunkSize = (total + threads - 1) / threads`，第 i 块 `[i*chunkSize, min(start+chunkSize-1, total-1)]` |
| 请求 | 每块独立 `Range: bytes=start-end` + `Accept-Encoding: identity` |
| 落地 | `<id>.<format>.part.<index>`（**独立文件**，不用 `RandomAccessFile` 定位写同一文件，避免并发定位互相破坏） |
| 合并 | 按 index 顺序写入 `.merge`，校验长度 == total，通过后删原文件并 rename |
| 清理 | 成功、取消、失败三条路径都要删分块与标记 |

**为什么强制要求 206**：服务端若忽略 `Range` 返回 200，它给的是**整个文件**。若把它当分块内容写进去，合并结果必然错位。因此返回 200 时直接抛错，由 `download()` 捕获后**降级单线程**（§8.19 要求「不得直接失败」）。

### 15.4 分块复用必须绑定来源

分块文件会跨应用重启留存。若只用「长度对不对」判断能否复用，会有一个隐蔽的坑：

1. 从镜像 X 下到 90% 被打断；
2. 下次测速排序变了，改用镜像 Y；
3. Y 上同名文件**字节数相同但内容不同**（同版本重新构建）；
4. 已完成的块按长度被接受，缺的块从 Y 下载；
5. 合并后**总长度校验通过**，但内容是 X 与 Y 的混合；
6. Debian/Ubuntu 的 `sha256` 为空 → **没有任何下游校验能发现**。

因此每块配一个标记文件 `<...>.part.<N>.src`，内容 `"$url|$total"`；复用需**长度 + 标记**双重匹配，不符即删重下。这是 §8.19 之外额外加的加固。

### 15.5 失败回退链

分块失败 → `download()` 捕获 → 剥掉当前 URL，用**其余候选镜像**走单线程 `RootfsDownloader`。若也失败，抛出**原始**异常（保留首次失败的真实原因，而不是「重试也失败了」这种信息量更低的错）。

线程数上限 `MAX_THREADS = 8`，默认 `DEFAULT_THREADS = 4`。

---

## 16. APT 换源

### 16.1 三种源文件格式

| 发行版 | 文件 | 格式 |
| --- | --- | --- |
| Debian 12+ / Ubuntu 24.04+ | `/etc/apt/sources.list.d/*.sources` | **deb822**（多行 `Key: Value`） |
| 老版本 / 部分镜像 | `/etc/apt/sources.list` | 单行 `deb <url> <suite> <components>` |
| Alpine | `/etc/apk/repositories` | 每行一个仓库 URL（无 suite 概念） |

`switchTo()` 先读 `/etc/os-release` 的 `ID` 分派：`alpine` → Alpine 分支；`debian`/`ubuntu` → Debian 系分支；其他 → 明确报「不支持」。Debian 系内部**优先 deb822**（若 `sources.list.d` 下有 `*.sources` 且含 `URIs:`），否则退回旧式单行。

### 16.2 arm64 的路径陷阱

> ⚠️ **这是换源最常见的失败原因。**

| 发行版 | x86 路径 | **arm64 实际路径** |
| --- | --- | --- |
| Debian | `.../debian` | **`.../debian-ports`** |
| Ubuntu | `.../ubuntu` | **`.../ubuntu-ports`** |

本项目只有 `arm64-v8a`。用 x86 路径会让 `apt update` 直接 404。`AptMirror` 数据类因此同时提供 `debianBase` 与 `alpineBase`，Ubuntu 走 `ubuntuPortsBase(mirror)` 推导——路径差异被封装在数据层，不在调用点。

### 16.3 代号来自 os-release，不能硬编码

`bookworm` / `trixie` / `noble` / `jammy` 必须从 rootfs 内读取。兜底链：

```
VERSION_CODENAME → UBUNTU_CODENAME → 从 VERSION / PRETTY_NAME 的括号内解析 → "stable"
```

> ⚠️ 兜底链曾经写成 `x.ifBlank { x }`（读同一个键）——那是死代码，取不到代号时会静默退化成字面量 `stable`，而 **Ubuntu 不认识 `stable`**，`apt update` 报 `E: The repository '... stable Release' does not have a Release file`。现在补上了 `UBUNTU_CODENAME`（部分精简 Ubuntu 镜像只提供这一个）与括号解析。

deb822 重写时**保留 `Signed-By`**：它指向签名密钥文件，丢了会导致 `NO_PUBKEY` 错误。

### 16.4 幂等与可逆

| 机制 | 实现 |
| --- | --- |
| 备份 | 后缀 `.prootterm.bak`（`BACKUP_SUFFIX`） |
| **幂等** | `backupOnce()` **发现备份已存在就不覆盖**——否则第二次换源会把「已换过的源」当成原始源备份，原始源永久丢失 |
| 可逆 | `restoreOriginal()` 从备份恢复，并删除备份 |
| 诚实 | 找不到备份时返回 `Failure("没有找到备份")`，**不假装成功**（§8.21） |
| 原子 | `writeAtomic` / `copyAtomic` 都是「写临时文件 + rename」，中断不会留下半个文件让 apt 完全不可用 |

### 16.5 已运行会话的注意事项

换源直接改宿主侧 rootfs 的文件，**不影响已运行的 guest**——它的 apt 进程已加载了旧配置，且部分发行版有缓存。UI 给出 `apt_running_warning` 提示，重启会话即生效。若要更友好，可在换源后向运行中的实例 `sendCommand("apt update")` 主动刷新（当前未实现，见 §19 第 23 条）。

---

## 17. 存储位置抽象

### 17.1 两个位置都不需要权限

| 位置 | 实际路径 | 获取方式 |
| --- | --- | --- |
| `INTERNAL` | `/data/data/<pkg>` | `context.filesDir` |
| `EXTERNAL` | `/Android/data/<pkg>` | `context.getExternalFilesDir(null)` |

两者都是**应用私有沙箱**：自 Android 4.4 起无需任何权限即可读写。这正是 §8.2 能「不申请任何存储权限、也不用 SAF」的原因——我们从不碰公共目录。

### 17.2 降级

`RootfsManager` 每次构造时读 `AppPrefs.storageLocation`。**外部位置不可用**（未挂载 / `getExternalFilesDir` 返回 `null`）时自动降级到内部并置 `locationFallback = true`。UI **必须如实提示**，否则用户以为数据在外部而实际在内部。

`StorageLocation.isAvailable` 判定用 `Environment.getExternalStorageState(dir) == MEDIA_MOUNTED`（该重载 API 21+，安全）。

### 17.3 切换只影响后续安装

不迁移已装数据。理由：跨文件系统 `renameTo` 不可靠，逐文件复制几十万小文件既慢又可能中断，风险远大于收益。为避免误解：

- 切换前先结束**当前存储位置上**运行中的实例（只结束受影响的那个，见 §13——曾经这里会误杀所有实例）；
- 版本列表用 `InstalledElsewhere` 状态明示「装在别处」，按钮为「重新下载」；
- 「我的系统」空列表时区分「一个都没装」与「装在另一个位置」。

---

---

## 18. 开机自启动链路

```
BOOT_COMPLETED / MY_PACKAGE_REPLACED
  → BootReceiver：只读 AppPrefs（autoStart + autoStartId），**不做**存在性判断
  → ProotService.launch(autoStartIntent)      # API 26+ 用 startForegroundService
  → ProotService：进前台 → 读清单校验版本存在 → 校验 isInstalled
                  → SessionManager.startBlocking(id, ...)
  → 失败则通知显示原因并 releaseIfIdle()
```

### 18.1 为什么校验放在服务里

广播接收者有 **10 秒**执行预算，且开机瞬间外部存储可能尚未挂载。在 `BootReceiver` 里读清单、查文件、解压 native 库很容易超时被杀。因此接收者只做一件事：读出偏好、发出意图。所有重活交给前台服务——它没有 10 秒限制，且已经进前台。

### 18.2 不处理 `LOCKED_BOOT_COMPLETED`

直接启动（Direct Boot）阶段应用目录仍处于凭据加密锁定状态，rootfs 读不到。因此有意不声明 `directBootAware`，也不监听 `LOCKED_BOOT_COMPLETED`。

### 18.3 无法绕过的系统限制

| 限制 | 说明 |
| --- | --- |
| 用户「强制停止」后 | 系统不再投递 `BOOT_COMPLETED`，必须重新打开一次应用 |
| OEM ROM（MIUI/EMUI/ColorOS） | 需用户在系统设置里额外允许「自启动」「后台运行」，应用侧无法绕过 |
| 开机瞬间外部未挂载 | 因此 `PROOT_TMP_DIR` / `native/` / `logs/` 已固定内部（§8.14），正好规避 |

---

## 19. 已知问题与风险清单

> 按影响排序。标注「已修复」的保留在列表中，以便回归时知道曾经踩过什么。

| # | 问题 | 成因/现状 | 影响 | 处置 |
| --- | --- | --- | --- | --- |

| 1 | **非 PTY 导致无提示符、无回显** | `ProotSession.kt:84-116` 用 `ProcessBuilder` + 管道连接 shell，未分配端终端（无 `pty`/`openpty`） | guest 内 shell 不会打印提示符，也不回显用户输入；`Ctrl+C`、`Ctrl+D`、Tab 补全、上下键历史等依赖 tty 的功能均无效 | 已在 UI 侧补偿：`MainActivity.sendCommand` 本地回显 `$ <cmd>`（`:484`）；`startShell` 输出三行提示，建议用户输入 `sh -i`（`:435`）。根本解决需引入 PTY（如 JNI 调 `openpty`），属于大改 |
| 2 | **Debian 三个版本 sha256 为空，跳过完整性校验** | `rootfs_manifest.json:60, 70, 80` 三处 `"sha256": ""`；`Sha256Utils.matches` 对空串直接 `return true`（`:27`），`MainActivity.install` 也用 `if (d.sha256.isNotBlank())` 整段跳过（`:342`） | 下载被中间人篡改、CDN 返回错误页、或 `.part` 因换镜像而拼接损坏时，**不会**被检出，会直接进入解压并最终落盘 | 从 termux/proot-distro release 页面取得官方 sha256 补入清单；或在 `formats` 分发时对 tar.xz 增加内建完整性检查 |
| 3 | **硬链接重试无上限，靠 progress 标志收敛** | `RootfsExtractor.kt:147-164`：`while (progress && pendingLinks.isNotEmpty())`，只有整轮零进展才退出 | 正常情况下每轮至少成功一个链接，故最坏 O(n²) 而非无限循环。但若某轮出现了「成功一个、失败一个」的极端交替，轮数会拉长；且最终无法解析的条目被**静默丢弃**（`:160-162` 只 warn） | 可加一个最大轮数上限（如 `pendingLinks.size + 1` 轮）与最终残留条目的汇总告警；当前实现在真实 rootfs 上未观察到问题 |
| 4 | ⚠️ **MainActivity 单文件已膨胀到约 1700 行，可维护性显著下降** | `MainActivity.kt` 现含：五屏导航、两个 RecyclerView 装配、快捷键绑定与 IME 加固、设置页填充、存储位置切换、自启动目标选择、**实例设置对话框（资源限制 + 镜像 + 换源）**、安装编排（含测速与多线程下载）、会话启动、终端输出渲染与**焦点实例过滤**、返回键与各类对话框 | 修改任一方面都要在近两千行里定位；安装协程体与 UI 逻辑高度耦合；新增的实例设置逻辑进一步加剧 | **本轮新增已经让这个问题从"偏低"变成"需要处理"**。建议优先拆出 `InstanceSettingsController`（本轮新增的对话框 + 换源）、`InstallCoordinator`、`TerminalController`、`ScreenNavigator`。这是当前最值得做的重构 |
| 5 | ✅ **已解决**：会话现在由前台服务保活，退出应用不再中断 shell | 本轮引入 `SessionManager`（进程内单例）+ `ProotService`（前台服务）。`MainActivity.onDestroy` 只取消安装任务，**不再** destroy 会话（§8.13）；会话结束后由 `watchSessionEnd()` 自动收掉服务 | 「后台保持运行」现在是真的：切页、退到桌面、Activity 被回收都不影响 shell | 见下方第 16 条：系统回收服务时的通知缺失是这一设计的固有取舍 |
| 6 | ~~无法在 UI 上取消进行中的安装~~ **（已修复）** | `item_version.xml` 的 `btn_cancel_install` + `VersionAdapter.State.Cancelling` + `MainActivity.cancelInstall` 同时 `downloader.cancel()` + `installJob.cancel()` | 取消后 `.part` 保留可续传；`Cancelling` 态按钮置灰防重复点击 | — |
| 7 | ~~`uninstall` 硬编码 `.part` 后缀~~ **（已修复）** | 改为 `RootfsManager.deletePartFiles(id)`，按 `"$id."` 前缀 + `".part"` 后缀扫描，**两个存储位置都扫** | 将来清单引入 `tar.zst` 等格式时不会残留 `.part`；`uninstall` 也会清掉 `rootfs/<id>.old` 备份 | — |
| 8 | ~~CTRL 字母快捷键当前无对应控件~~ **（已修复）** | `buildLetterRow()` 从 `item_ctrl_key.xml` inflate 26 个按钮，随 `scroll_ctrl_letters` 在 CTRL/ALT 待命时显示 | CTRL 发 `0x01–0x1A`、ALT 发 `ESC+字母`，均为**真实控制序列**（§8.16） | — |
| 9 | **`%` 与 Kotlin 字符串模板混用处的格式化风险** | `MainActivity.install` 用 `"... ${d.name} … $pct%（%.1f / %.1f MB）".format(...)`，格式串中同时含模板与 `%` 占位符 | 若发行版 `name` 含 `%`（如清单作者写 `"100%"`），`.format()` 会抛 `UnknownFormatConversionException` | 当前清单 name 均为纯数字/字母，未触发。稳妥做法是 `String.format(Locale.US, ...)` 并转义 name，或改用拼接 |
| 10 | **`File.renameTo` 的跨文件系统假设** | `RootfsManager.finalizeInstall` 依赖 `temp.renameTo(dest)` | 本轮起 `tempDir` 与 `distroDir` **同源于同一个 `sandboxDir`**，因此必然同分区，跨文件系统风险已被结构性消除 | 保留降级为「拷贝 + 删除源」的兜底路径可进一步增强鲁棒性 |
| 11 | **API 24/25 上 `destroy()` 可能长时间阻塞 reaper 线程** | `ProotSession.destroy()` 在 `SDK_INT < O` 时调用无超时的 `p.waitFor()` | 生成一个可能长期驻留的 `proot-reaper` 守护线程（`isDaemon = true`，不阻塞 UI），但进程若僵死无法强杀（`destroyForcibly` 不可用） | API 24/25 的硬限制。可选的改进是在低版本上先向 guest 写 `exit\n` 走优雅退出 |
| 12 | **输出缓冲截半会丢失历史** | `ProotSession` 与 `MainActivity` 各有一层 `MAX_OUTPUT_CHARS = 200_000` 截断，超出丢头部一半 | 大量输出（如 `find /`）会一次性丢掉一半历史；由于是「丢头保尾」，用户正在看的中段可能消失 | 有意的性能取舍（摊还成本）。`ProotSession.truncatedChars` 会记录被丢弃的字符数，UI 在输出顶部显示 `output_truncated` 提示，不再静默丢弃 |
| 13 | ✅ **已修复**：终端输出不再刷爆日志 | 本轮移除了 `ProotSession.readLoop` 中逐块的 `AppLogger.d("ProotOut", ...)`，只记启动命令、环境变量、退出码 | 修复前交互式命令几分钟就能滚满 1MB 环形缓冲，把「启动失败」等关键信息挤掉，排障时看不到真正原因 | `AppLogger` 仍是每条日志开关一次文件；若成为瓶颈可引入缓冲写入 + 定期 flush（代价是崩溃时可能丢最后几条，与崩溃前 200ms 等待策略冲突，需权衡） |
| 14 | ~~`ManifestLoader` 未校验 `version` 字段~~ **（已修复）** | 现校验顶层 `version == SUPPORTED_VERSION`，并校验每个版本的 `format` 属于支持集合、拒绝空清单 | 清单格式演进时明确失败而非静默解析出半份数据 | — |
| 15 | **状态恢复仅覆盖 `activeInstallId` 对应的那一项** | `openVersions` 只对 `manager.isInstalled(id)` 与 `activeInstallId == id` 两种情况设状态 | `lastDownloadPct` 是**单个全局变量**，不是按 id 的 Map——由于闸门保证同时只有一个安装任务，当前不构成问题 | 若支持并发安装，需改为 `HashMap<String, Int>` |
| 16 | ⚠️ **系统回收前台服务后，仍在运行的会话会暂时没有通知** | `ProotService.onDestroy` 按 §8.13 **故意不杀会话**；`onStartCommand` 收到空 intent 时走 `else ->` 分支直接 `stopSelfSafely()`（`START_STICKY` 重建后不带 action） | 若系统（而非用户）销毁了服务，shell 会按设计继续运行，但通知消失、通知栏「结束」按钮也随之消失。此时唯一的结束途径是重新打开应用（应用内「结束」会直接调 `SessionManager.stop()`） | **这是 §8.13 的固有取舍，不是缺陷**：宁可短暂缺通知，也不能因为服务被回收就把用户正在跑的编译/下载任务杀掉。若要改善，可在 `App.onCreate` 或下次进入应用时检测「`state != IDLE` 但没有前台服务」并主动重建，属独立增强 |
| 17 | ⚠️ **外部存储位置在 Android 11+ 上可能无法执行 proot** | `StorageLocation.EXTERNAL` → `getExternalFilesDir(null)` = `/Android/data/<pkg>`，该目录由 FUSE 提供，通常带 noexec 语义；`ProotSession.start()` 会因 `Permission denied` 失败 | 用户选了外部存储并装好系统后，点「启动」可能直接失败 | 已由 `ProotSession.explainStartFailure()` 把该错误翻译成「请把存储位置改为内部存储后重新安装」的可操作提示。彻底的解法是让 rootfs 固定内部、只把大文件缓存放外部，属架构调整 |
| 18 | ⚠️ **切换存储位置不迁移已装系统** | `MainActivity.onStorageSelected` 只改 `AppPrefs.storageLocation` 并结束运行中的会话；`RootfsManager` 按新位置解析 `sandboxDir` | 切换后「我的系统」列表会变空（实际数据还在旧位置），用户可能误以为系统丢了 | 已缓解：`banner_other_location` 常驻提示条 + `VersionAdapter.State.InstalledElsewhere`（按钮显示「重新下载」而非「启动」）明确告知用户。**不自动迁移**是有意的：跨文件系统 `renameTo` 不可靠，逐文件复制几十万小文件既慢又可中断。若要支持迁移，应做成显式「迁移」按钮 + 前台服务 + 进度通知 |
| 19 | ⚠️ **多实例并存会成倍消耗内存与 CPU** | `SessionManager` 用 `Map<id, SessionSlot>` 允许任意多个 `ProotSession` 同时存活，每个都是独立的 proot 进程树 + 独立输出缓冲（各 `MAX_OUTPUT_CHARS = 200_000`） | 同时运行 3~4 个实例时，仅输出缓冲就可能占用数百 KB（StringBuilder 是 UTF-16，实占 2 倍），加上每个 guest 内的进程，低端设备可能被系统整体回收，反而全部丢失 | **已有资源限制功能作为缓解手段**（§14），但默认是「不限制」。可考虑：① 对输出缓冲改用 `CharArray` 或限制实例数上限；② 在「我的系统」页提示当前实例数与内存占用 |
| 20 | ⚠️ **后台实例的输出以全速累积，无人查看也会占内存** | `ProotSession.readLoop` 对每个实例都在 `mainHandler.post` 里 `appendInternal(chunk)`，与是否有 UI 观察者无关 | 一个被切走的实例狂刷输出（如 `yes`）时，其缓冲会不断触发「截掉一半」的逻辑，白白消耗 CPU | 可选的优化：非焦点实例降低刷新频率或暂停累积（需注意用户切回来时应当仍有合理的历史）。当前设计选择是「绝不丢数据」，代价是 CPU |
| 21 | ⚠️ **`ulimit -v` 限制的是地址空间，不是物理内存** | `ResourceLimits.toScript` 把 MB×1024 后交给 `ulimit -v`（即 `RLIMIT_AS`） | UI 写「虚拟内存上限 512MB」会让用户以为「最多占 512MB 物理内存」，实际可能远超；反之设得过低会让动态链接器映射失败导致 shell 直接起不来 | 文案已如实说明（§8.18 明文要求，`limits_memory_hint`），但概念本身容易误解。彻底解决需 cgroup，免 root 下不可得 |
| 22 | ⚠️ **多线程分块下载对镜像施加并发压力，可能触发限流** | `MultiPartDownloader` 默认 4 线程、上限 8，对同一 URL 并发发 `Range` 请求 | 部分公益镜像会按 IP 限流或拒绝并发 Range 请求，表现为「单线程能下、多线程失败」 | 已有兜底：分块失败会改用**其余候选镜像**走单线程重试；且线程数可配置为 1。可考虑在连续失败后自动降线程并记忆 |
| 23 | ⚠️ **换源后 guest 内已运行的 apt 仍用旧源** | `AptSourceSwitcher` 直接改宿主侧 rootfs 文件，不重启 guest | 用户换源后立刻在终端里 `apt update` 可能仍读到旧缓存；UI 已有 `apt_running_warning` 提示 | 属预期行为（重启会话即生效）。若要更友好，可在换源后 `sendCommand` 一条 `apt update` 让 guest 主动刷新 |

---

## 20. 附录：关键常量速查

| 常量 | 值 | 定义位置 | 用途 |
| --- | --- | --- | --- |
| `MAX_OUTPUT_CHARS` | `200_000` | `MainActivity.kt:590` | 终端输出缓冲上限，超过后丢头部一半 |
| `MB` | `1024.0 * 1024.0` | `MainActivity.kt:591` | 进度文案的 MB 换算 |
| `GITHUB_URL` | `https://github.com/LiStudioorg/linuxandroid/` | `MainActivity.kt:592` | 设置页链接 |
| `PAYLOAD_PROGRESS` | `"payload_progress"` | `VersionAdapter.kt:134` | RecyclerView 局部刷新标识 |
| `MODE_MASK` | `0x1FF` | `RootfsExtractor.kt:207` | 权限位掩码（`0777`） |
| `COPY_BUFFER` | `128 * 1024` | `RootfsExtractor.kt:26` | 解压拷贝缓冲 |
| `FORMAT_TAR_GZ` / `FORMAT_TAR_XZ` | `"tar.gz"` / `"tar.xz"` | `RootfsExtractor.kt:23-24` | 支持的归档格式 |
| `PROGRESS_INTERVAL_MS` | `200L` | `RootfsDownloader.kt:178` | 下载进度节流间隔 |
| `BUFFER_SIZE` | `128 * 1024` | `Sha256Utils.kt:10` | SHA256 流式读取缓冲 |
| `MARKER` | `".installed"` | `RootfsManager.kt`（companion） | 安装完成标记文件名 |
| 备份目录后缀 | `"<id>.old"` | `RootfsManager.backupDir()` | 升级安装的回滚点，成功后删除 |
| `ASSET_DIR` | `"native/arm64-v8a"` | `NativeDeps.kt:21` | 运行库 assets 路径 |
| `LIBS` | `["libtalloc.so.2", "libandroid-shmem.so"]` | `NativeDeps.kt:23` | 需释放的运行库列表 |
| `ASSET_NAME` | `"rootfs_manifest.json"` | `ManifestLoader.kt` | 清单文件名 |
| `SUPPORTED_VERSION` | `2` | `ManifestLoader.kt` | 顶层 `version` 必须等于该值 |
| `KEY_ESC`/`KEY_UP`/`KEY_DOWN`/`KEY_LEFT`/`KEY_RIGHT` | `"\u001b"` / `"\u001b[A"` … | `MainActivity.kt`（companion） | 终端控制序列，禁止裸控制字符 |
| `MAX_MEMORY_ENTRIES` | `2000` | `AppLogger.kt:19` | 内存日志环形缓冲条数 |
| `MAX_FILE_BYTES` | `1024L * 1024L` | `AppLogger.kt:20` | `app.log` 滚动阈值（1MB） |
| 下载超时 | connect 20s / read 60s / write 60s | `RootfsDownloader.kt:25-27` | OkHttpClient 配置 |
| `PROGRESS` | `retryOnConnectionFailure(true)` | `RootfsDownloader.kt:28` | 连接层重试 |
| `extractNativeLibs` | `true`（**合成值**） | 源码 Manifest 中**未显式声明**；由 `app/build.gradle.kts:76` 的 `useLegacyPackaging = true` 让 AGP 在合并 Manifest 时合成 | 必须解压 native 库，否则 `libproot.so` 无法从 `nativeLibraryDir` 执行 |
| `useLegacyPackaging` | `true` | `app/build.gradle.kts:76` | 传统 jniLibs 打包（与上一行同源，二者不得矛盾） |
| `launchMode` | `singleTask` | `AndroidManifest.xml:18` | MainActivity 单实例 |
| `SHELL_MARKER` | `".shell"` | `RootfsManager.kt`（companion） | 记录安装时清单声明的 shell，清单不可用时仍准确 |
| `DIR_ROOTFS` | `"rootfs"` | `StorageLocation.kt`（companion） | rootfs 子目录名，两个存储位置共用 |
| `ABORTED` | `"\u0000aborted\u0000"` | `SessionManager.kt`（companion） | `startBlocking` 的「已取消」哨兵，**不可与 `null`（成功）混用** |
| `SNAPSHOT_TIMEOUT_MARK` | `"[ProotTerm] 读取会话输出超时，请稍后重试\n"` | `ProotSession.kt`（companion） | 快照超时哨兵；返回空串会被 UI 误判成「无输出」 |
| `SNAPSHOT_TIMEOUT_MS` | `2_000L` | `ProotSession.kt` | 跨线程取快照的等待上限 |
| `WATCH_INTERVAL_MS` | `1500L` | `ProotService.kt` | 会话结束探针轮询间隔 |
| `NOTIF_ID` | `1001` | `ProotService.kt`（companion） | 常驻通知 id |
| `ACTION_START` / `ACTION_STOP` / `ACTION_AUTO_START` / `ACTION_OPEN_TERMINAL` | 字符串 | `ProotService.kt`（companion） | 服务动作；`ACTION_STOP` 是会话**唯一权威**结束点 |
| `EXTRA_VERSION_ID` | `"version_id"` | `ProotService.kt`（companion） | 启动意图携带的系统 id |
| `MAX_OUTPUT_CHARS` | `200_000` | `ProotSession.kt` + `MainActivity.kt` | 双层输出缓冲上限（会话层 + UI 层） |
| `epoch` | `AtomicInteger` | `SessionManager.kt` | 会话代次；`prepare()`/`stop()` 自增，`startBlocking` fork 前后各校验一次 |
| 存储位置 key | `"internal"` / `"external"` | `StorageLocation.kt` | 写入 `AppPrefs.storage_location` |
| `prootterm_settings` | SharedPreferences 名 | `AppPrefs.kt` | 存储位置 / 自启动 / 最近系统的持久化 |
| `ROOTFS_ROOT` 相关 | `rootfs/`、`tmp_<id>/`、`<id>.<format>.part`、`rootfs/<id>.old/` | `RootfsManager` | 随存储位置变化；`native/`、`proot_tmp/`、`logs/` **固定内部** |
| `SH_PATH` | `"/bin/sh"` | `ResourceLimits.kt`（companion） | 包装脚本用的解释器；guest 内必然存在（Debian/Ubuntu 是 dash，Alpine 是 busybox ash），**不能假设有 bash** |
| `UNLIMITED` | 五项全 0 | `ResourceLimits`（companion） | 默认值；`toScript()` 返回 `null`，不生成任何 `ulimit` |
| `PRESETS` | 4 组预设 | `ResourceLimits`（companion） | 实例设置对话框的「预设」按钮 |
| `SH_PATH` 包装 argv | `[/bin/sh, -c, script]` | `ResourceLimits.toGuestCommand()` | **必须 3 个独立 argv**（§5.2），单字符串会让 proot 当文件名解析 |
| `instances.json` | `filesDir/instances.json` | `InstanceStore.FILE_NAME` | 每实例配置；**固定内部**，不随存储位置 |
| `InstanceStore.of()` | 进程内单例 | `InstanceStore`（companion） | `MainActivity` 与 `ProotService` **必须**共用，否则 UI 保存的限制服务读不到 |
| `DEFAULT_THREADS` | `4` | `InstanceConfig`（companion） | 分块下载默认线程数 |
| `MAX_THREADS` | `8` | `MultiPartDownloader`（companion） | 分块下载线程上限（同时约束 `safeThreads` 与清理循环） |
| `MIN_MULTIPART_BYTES` | `8L * 1024 * 1024` | `MultiPartDownloader`（companion） | 小于此值不分块，走单线程 |
| `CHUNK_BUFFER` | `128 * 1024` | `MultiPartDownloader` | 分块写入缓冲 |
| `PROGRESS_INTERVAL_MS`（分块） | `200L` | `MultiPartDownloader` | 分块进度节流 |
| 分块命名 | `<id>.<format>.part.<N>` | `MultiPartDownloader.chunkFile()` | 合并后删除；`.src` 为来源标记 |
| 分块来源标记 | `url + "\|" + total`（实际内容用竖线分隔） | `MultiPartDownloader.chunkToken()` | 复用需**长度 + 标记**双匹配（§15.4） |
| 合并中间文件 | `<part>.merge` | `MultiPartDownloader.mergeChunks()` | 校验 `length == total` 后才 rename |
| `PER_MIRROR_TIMEOUT_MS` | `4_000L` | `MirrorProbe.kt` | 单镜像测速超时 |
| `TOTAL_TIMEOUT_MS` | `6_000L` | `MirrorProbe.kt` | 整批测速超时；超时则回退清单顺序 |
| `FAILED_LATENCY` | `Long.MAX_VALUE` | `MirrorProbe`（companion） | 探测失败的排序值，排在最后 |
| `BACKUP_SUFFIX` | `".prootterm.bak"` | `AptSourceSwitcher` | 换源备份后缀；`backupOnce` **不覆盖已有备份** |
| `DEB822_DIR` | `"etc/apt/sources.list.d"` | `AptSourceSwitcher` | 新式 deb822 源目录 |
| `LEGACY_LIST` | `"etc/apt/sources.list"` | `AptSourceSwitcher` | 旧式单行源文件 |
| `ALPINE_REPO` | `"etc/apk/repositories"` | `AptSourceSwitcher` | Alpine 仓库列表，格式与 apt 无关 |
| `EXTRA_INSTANCE_ID` | `"instance_id"` | `ProotService`（companion） | `ACTION_STOP` 的实例范围；缺省=停全部 |
| `epoch` | `AtomicInteger`（**每槽一个**） | `SessionManager.SessionSlot` | **不再是全局单次计数**；`prepare`/`stop` 自增该槽，`startBlocking` fork 前后各校验一次 |

| `configChanges` | `orientation\|screenSize\|screenLayout\|keyboardHidden` | `AndroidManifest.xml:16` | 避免旋转重建、保住 shell 会话 |

**构建与 CI**

| 项 | 值 |
| --- | --- |
| JDK | 17（`compileOptions` / `kotlinOptions.jvmTarget`，`app/build.gradle.kts:65-72`） |
| 签名优先级 | 环境变量 > `local.properties` > 兜底 `myapp.jks`（`app/build.gradle.kts:33-51`） |
| 签名方案 | V2/V3 开启，V1/V4 关闭（`app/build.gradle.kts:47-50`） |
| `isMinifyEnabled` | `false`（`app/build.gradle.kts:56`） |
| CI 触发 | `push` 任意 tag → 构建 + Release；`workflow_dispatch` → 仅 Artifact（`.github/workflows/android.yml:3-7, 55-62`） |
| CI Secrets | `KEYSTORE_BASE64`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD` |
| CI 步骤 | Checkout → JDK17 → Accept SDK Licenses → Decode Keystore → chmod gradlew → `./gradlew assembleRelease` → Upload Artifact → Release（**无 Fetch PRoot 步骤**） |

**终检命令**

```bash
grep -r "com.example" app/src/main/java/          # 必须为空
grep -rn "java.nio.file\|toPath()" app/src/main/java/   # 必须为空（§8.6）
grep -rn "READ_EXTERNAL_STORAGE\|WRITE_EXTERNAL_STORAGE\|MANAGE_EXTERNAL_STORAGE" \
     app/src/main/                                 # 必须为空（§8.2）
grep -n "ACTION_OPEN_DOCUMENT_TREE" app/src/main/java/   # 必须为空（§8.2 禁用 SAF）
grep -n "textPassword\|textWebPassword" app/src/main/res/layout/view_terminal.xml
                                                   # 必须为空（§8.15 安全键盘）
# §8.13 两个 onDestroy 都不得**调用** SessionManager.stop()（先剔除注释）
strip_c() { sed 's://.*::' "$1" | sed '/^\s*\*/d'; }
for f in MainActivity ProotService; do
  strip_c "app/src/main/java/com/li63050a/linuxandroid/$f.kt" \
    | sed -n '/override fun onDestroy/,/^    }$/p' | grep -qE 'SessionManager\.(stop|stopAll)\(' \
    && echo "违规：$f.onDestroy 在杀会话" || echo "$f.onDestroy OK"
done
# §8.17 不得存在无参「当前会话」访问器
grep -nE 'fun (sessionOrNull|currentSession|snapshot)\(\)' \
     app/src/main/java/com/li63050a/linuxandroid/SessionManager.kt   # 必须为空
# §8.18 资源限制必须是 argv 列表（不得把脚本拼成单个字符串）
grep -n 'toGuestCommand' app/src/main/java/com/li63050a/linuxandroid/ProotSession.kt
grep -rn 'toShellPrefix' app/src/main/java/                        # 必须为空（已删除）
# §8.19/§8.20 分块复用必须绑定来源标记
grep -n 'chunkToken\|chunkMarker' app/src/main/java/com/li63050a/linuxandroid/MultiPartDownloader.kt
grep -n '206' app/src/main/java/com/li63050a/linuxandroid/MultiPartDownloader.kt
# §8.21 换源备份不得覆盖
grep -n 'BACKUP_SUFFIX\|existing != null\|exists()' app/src/main/java/com/li63050a/linuxandroid/AptSourceSwitcher.kt
# §8.14 三个路径必须固定在内部（不得读 location）
grep -n -A3 "fun prootTmpDir\|fun logsDir\|fun nativeDir" \
     app/src/main/java/com/li63050a/linuxandroid/RootfsManager.kt
ls -l gradle/wrapper/                              # jar + properties 齐全
ls -l app/src/main/jniLibs/arm64-v8a/              # libproot.so + libproot-loader.so
ls -l app/src/main/assets/native/arm64-v8a/        # libtalloc.so.2 + libandroid-shmem.so
git remote -v                                      # https://github.com/LiStudioorg/linuxandroid.git
./gradlew assembleDebug
```
