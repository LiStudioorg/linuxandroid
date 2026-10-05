# AGENTS.md — LinuxAndroid 技术方案

本文件面向 AI 编码代理与开发者，规定项目结构、实现方案与编码约定。修改代码前必须通读。

## 1. 项目概述

LinuxAndroid（界面品牌名 **ProotTerm**）是一个免 root 的 Android 应用：内置 PRoot（Termux 适配版）二进制，在应用私有目录解压用户选择的 Linux rootfs（Alpine / Debian / Ubuntu），并通过一个简易文本终端驱动其中的 shell。

- applicationId / namespace / Kotlin 包名：**com.li63050a.linuxandroid**（三者一致，Manifest 的 `.MainActivity` 依赖 namespace 解析，源码包名不得再改回其他值）
- versionCode 1 / versionName `0.0.0.1`
- 目标架构：仅 `arm64-v8a`
- 语言：Kotlin 100%，界面：XML 布局 + **AppCompat + Material Components**（DrawerLayout / NavigationView / MaterialToolbar / MaterialCardView / RecyclerView，不引入 Compose）
- **多系统并存 · 多实例隔离**：可同时安装多个 rootfs（Alpine/Debian/Ubuntu 各版本），「我的系统」页统一管理；**多个实例可同时运行**，每个实例拥有独立的会话、输出缓冲与资源配置（互不干扰）
- **资源限制**：每个实例可单独配置虚拟内存 / 进程数 / 文件描述符 / CPU 时间上限（ulimit 语义，非 root 下 cgroup 不可用），改后重启该实例会话生效
- **镜像增强**：多镜像并发测速自动优选、可自定义镜像源、多线程分块并发下载（服务端不支持 Range 时自动降级单线程）
- **APT 换源**：一键为 Debian/Ubuntu/Alpine 切换国内软件源，自动识别发行版代号，可一键恢复原始源
- **存储位置可选**：rootfs 可放在内部 `/data/data/<pkg>` 或外部 `/Android/data/<pkg>`，两者都无需任何存储权限，设置页随时切换（只影响后续安装，不自动迁移已装数据）
- **会话可脱离界面存活**：shell 由 `ProotService` 前台服务保活，退到后台仍继续执行；支持开机自启动（`BootReceiver`）
- 日志：`AppLogger` → 内存环形缓冲 + `filesDir/logs/app.log`（1MB 滚动 `.old`）+ Logcat；`LogActivity` 查看/复制/一键分享（FileProvider `content://`）
- 不申请任何存储权限（仅 `INTERNET` + 开机自启/前台服务相关权限），全部使用应用私有目录
- Git 远程：`https://github.com/LiStudioorg/linuxandroid.git`（HTTPS；协议变更需维护者确认）

## 2. 技术栈与依赖

| 依赖 | 版本 | 用途 |
| --- | --- | --- |
| OkHttp | 4.12.0 | rootfs 下载、Range 断点续传、多镜像回退 |
| Apache Commons Compress | 1.26.0 | tar 流解析 |
| org.tukaani:xz | 1.9 | tar.xz 解压 |
| kotlinx-coroutines-android | 1.8.0 | 异步任务、生命周期作用域 |
| Android framework `org.json` | 内置 | 解析 `rootfs_manifest.json`（不引入 kotlinx-serialization） |

构建链：

| 项 | 版本 |
| --- | --- |
| AGP | 8.11.1 |
| Kotlin | 2.0.21 |
| Gradle（wrapper） | 8.13（`gradle/wrapper/gradle-wrapper.properties`） |
| JDK | 17 |
| compileSdk / targetSdk | 36 |
| minSdk | **24**（Android 7.0 ~ Android 16） |

### minSdk 24 硬性约束

以下 API 为 26+，**必须**用 `Build.VERSION.SDK_INT >= Build.VERSION_CODES.O` 分支保护或改用低版本等价实现：

- `Process.isAlive` / `Process.waitFor(long,TimeUnit)` / `Process.destroyForcibly`（见 `ProotSession`）
- `java.nio.file.Files` / `toPath()`（**禁止使用**，见 `RootfsExtractor.deleteNoFollow` 改用 `Os.lstat` + `Os.remove`）
- `NotificationManager.getNotificationChannel` / `NotificationChannel` 构造（见 `ProotService.createChannel`）
- `Service.startForeground(int,Notification,int)` 三参重载（API 29+，见 `ProotService.startForegroundCompat`）
- `PendingIntent.FLAG_IMMUTABLE`（API 23+，见 `ProotService.pendingIntentFlags`）
- `View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS`（API 26+，见 `MainActivity.hardenImeForTerminal`）
- `Service.stopForeground(int)`（API 24+，见 `ProotService.stopSelfSafely`）

以下 API **已确认在 API 24 可用**，无需分支（曾担心高版本，实际查 AOSP `api/24.txt` 确认）：

- `Environment.getExternalStorageState(File)` / `isExternalStorageEmulated(File)`（API 21+）
- `OsConstants.S_IFMT` / `S_IFLNK`、`Os.lstat`（API 21+）
- `MaterialSwitch`（Material Components 1.7.0+，本项目 1.12.0）

`android.system.Os.*`（symlink/link/chmod/lstat/remove）自 API 21 可用，可直接使用。

### 签名配置（app/build.gradle.kts）

优先级：**环境变量（GitHub Actions 注入）> local.properties（本地）> 兜底 `myapp.jks`**。

- 环境变量 / local.properties 键名一致：`KEYSTORE_PATH`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`
- local.properties 已 gitignore；CI 见第 7 节 Secrets

## 3. 目录结构

```
AGENTS.md / README.md
docs/                             # 项目文档（当前 untracked，见第 9 节）
  README.md                       # 文档索引与角色导航
  CONTRIBUTING.md                 # 开发者入门：环境、代码地图、工作流、自检
  ARCHITECTURE.md                 # 深度架构：分层、核心链路、会话三层架构、存储位置、自启动、风险
  MODULES.md                      # 逐类 API 契约与坑
  TESTING.md                      # 测试策略、手工测试矩阵（本轮新增功能全覆盖）、回归清单
  TROUBLESHOOTING.md              # 按症状索引的故障排查手册
  COMPLIANCE.md                   # 对 §8 硬性约束的合规审查报告
settings.gradle.kts               # rootProject.name = "LinuxAndroid"
build.gradle.kts                  # AGP 8.11.1 + Kotlin 2.0.21
gradle.properties
gradlew / gradlew.bat             # Gradle 8.13 wrapper（入库，CI 直接 ./gradlew）
gradle/wrapper/{gradle-wrapper.jar, gradle-wrapper.properties}
.gitignore                        # 不排除 jniLibs *.so（已入库）
.github/workflows/android.yml     # tag → Release；workflow_dispatch → 仅产物（无 Fetch 步骤）
app/
  build.gradle.kts                # 包名/SDK/签名/packaging.jniLibs
  proguard-rules.pro
  src/main/
    AndroidManifest.xml           # .App + MainActivity + LogActivity + ProotService(前台) + BootReceiver + FileProvider
    assets/
      rootfs_manifest.json        # version 2：三发行版家族 → 多历史版本
      native/arm64-v8a/           # 运行期依赖（入库）
        libtalloc.so.2            # proot NEEDED；名字不以 .so 结尾不进 jniLibs
        libandroid-shmem.so       # proot NEEDED
    jniLibs/arm64-v8a/            # *.so 已入库（.gitignore 不排除）
      libproot.so                 # ← usr/bin/proot
      libproot-loader.so          # ← usr/libexec/proot/loader
      README.txt                  # deb 提取步骤（兜底说明）
    res/xml/file_paths.xml        # FileProvider paths（logs）
    res/layout/                   # 12 个布局
      activity_main.xml           # Drawer + NavigationView + 5 个 <include>
      activity_log.xml  nav_header.xml
      view_{myapps,terminal,distros,versions,settings}.xml   # 五个屏
      item_{myapp,distro,version,ctrl_key}.xml               # 列表项
    res/menu/nav_menu.xml         # 我的系统/终端/发行版/日志/设置（默认「我的系统」）
    res/drawable/                 # 12 个 vector，无 PNG
      ic_{launcher,menu,search,terminal,distro,settings,logs,back}.xml
      bg_{search,terminal,circle,badge}.xml
    res/values/{strings,themes,colors}.xml
    java/com/li63050a/linuxandroid/
      App.kt                      # 全局 Application：AppLogger + SessionManager 初始化 + 未捕获异常落盘
      AppLogger.kt                # 日志：内存缓冲 + logs/app.log（固定内部位置）+ Logcat
      LogActivity.kt              # 日志页：刷新/清空/复制/分享（FileProvider content://）
      DistroInfo.kt               # DistroFamily / DistroInfo 数据类（清单模型）
      MyApp.kt                    # MyApp 数据类 + InstalledScanner（扫描已安装系统）
      StorageLocation.kt          # 存储位置枚举：内部 filesDir / 外部 getExternalFilesDir
      AppPrefs.kt                 # SharedPreferences：存储位置 / 自启动 / 最近系统
      ManifestLoader.kt           # assets JSON 解析（version 2 嵌套结构）
      RootfsManager.kt            # 目录规划（按存储位置）、安装标记、原子切换
      RootfsDownloader.kt         # OkHttp 下载（断点续传 + 多镜像）
      RootfsExtractor.kt          # tar 解压（符号链接/权限/防穿越，API24 安全）
      NativeDeps.kt               # 释放 assets 运行库到 filesDir/native
      Sha256Utils.kt              # 流式 SHA256
      DistroAdapter.kt            # 家族卡片 RecyclerView 适配器
      VersionAdapter.kt           # 版本列表 RecyclerView 适配器（含「装在别处」态）
      MyAppAdapter.kt             # 「我的系统」列表适配器（含运行中徽标）
      ProotSession.kt             # 单个 shell 进程 + 输出缓冲（不持有 Activity）
      SessionManager.kt            # 多会话注册表：每实例独立槽/代次/状态机、观察者、命令写入
      ProotService.kt             # 前台服务：会话保活 + 常驻通知（唯一权威）
      BootReceiver.kt             # 开机自启动入口（BOOT_COMPLETED）
      MainActivity.kt             # Drawer 五屏编排（我的系统/终端/发行版/版本/设置）
```

## 4. 关键流程

### 4.1 安装（点击“下载”按钮）

```
下载 .part（断点续传）
  → SHA256 校验（清单 sha256 非空时）
  → 解压到 <sandbox>/tmp_<id>（保留符号链接与权限位，防路径穿越）
  → 若旧 <sandbox>/rootfs/<id> 存在，先 rename 为 <id>.old 备份（回滚点）
  → tmp 目录原子 rename 为最终目录；任一步失败则把 .old 挪回原位，旧安装不丢
  → 成功后删除 .old 备份，写入 .installed 标记，删除 .part 缓存
  → 按钮变为“启动”
```

`<sandbox>` 由当前存储位置决定（§4.5）。**temp 与 dest 必须同处一个存储位置**，
否则跨文件系统 `renameTo` 会失败——两者都取自同一个 `RootfsManager` 实例即可保证。

失败：删半成品 tmp；`.part` 保留供续传；按钮恢复“下载”。

### 4.2 下载（RootfsDownloader + MultiPartDownloader + MirrorProbeService）

- 候选地址：自定义镜像源（可选，仅 http/https）→ `url` → `mirrors[]`，失败自动切换，`.part` 保留
- 开启自动优选时先**并发测速**重排候选（`MirrorProbeService.rank`），单镜像 4s / 整体 6s 超时；
  **全部探测失败必须回退清单原顺序**，测速失败不得阻断下载（§8.20）
- 文件 ≥ 8MB 且线程数 > 1 时走 `MultiPartDownloader` 分块并发：切 N 块下到
  `<id>.<format>.part.<index>`，按序合并并校验总长；服务端返回 200（忽略 Range）即报错
- 分块复用需同时匹配**长度 + 来源标记**（`<...>.src` 记录 `url|total`）：
  镜像顺序按延迟排序且不稳定，仅凭长度复用会把别的镜像的同长度块拼进来（§8.19）
- 分块失败改用**其余候选镜像**走单线程重试；取消时清理全部分块
- 单线程路径：`Range: bytes=<n>-` 断点续传 + `Accept-Encoding: identity`
- 临时文件：`<sandbox>/<id>.<format>.part`
- 已有字节 > 0 时发 `Range: bytes=<n>-` + `Accept-Encoding: identity`
- `206`：校验 Content-Range 起点后追加；`200`：截断覆盖；`416`：视为已完整
- 实际字节 < 服务端总长 → 抛“下载不完整”进入下一镜像
- 进度回调节流 200ms；总长未知时 UI 兜底用清单 `size`（仅展示，不参与完整性判断）
- 取消：`cancel()` 结束活动 Call → `ensureActive()` 抛 CancellationException，不再切镜像；
  `MultiPartDownloader.cancel()` 需一并调用以清理分块临时文件

### 4.3 解压（RootfsExtractor，API 24 安全）

- `format` → `GzipCompressorInputStream` / `XZCompressorInputStream` 包 `TarArchiveInputStream`
- 路径穿越：条目名去前导 `/` 拼接后，`canonicalPath` 必须等于 dest 或以 `dest + separator` 开头
- 普通文件：`Os.chmod(path, entry.mode and 0x1FF)`（mode 0 跳过）
- 符号链接：`Os.symlink(entry.linkName, outFile.absolutePath)`，linkName 原文写入
- 删除已存在路径：`Os.lstat` + `Os.remove`（不跟随链接；**禁用 java.nio.file**）
- 硬链接：主循环收集，结束后多轮重试直至无进展
- 目录：解压期间保持默认可写，全部结束后逆序统一 `chmod` 还原

### 4.4 PRoot 启动（ProotSession + SessionManager + ProotService）

```
<nativeLibraryDir>/libproot.so
  -r <rootfs> -0 -w /root
  -b /dev -b /proc -b /sys -b /dev/urandom:/dev/random
  <defaultShell>                     # 无资源限制时
  /bin/sh -c '<ulimit …>; exec <defaultShell>'   # 有资源限制时（3 个独立 argv）
```

> ⚠️ **资源限制必须传 3 个独立 argv**（`/bin/sh`、`-c`、脚本文本）。proot **不会**
> 替你调用 shell：它把最后一个参数当**可执行文件路径**解析（`src/path/path.c` 的
> `which()` → `execvp`）。把整段脚本拼成单个字符串会让 proot 去找名为
> `ulimit -v …; exec …` 的文件，报 `'…' not found (root = …, $PATH=…)`，
> **凡是设了限制的会话一律启动失败**。脚本末尾的 `exec` 保证真 shell 替换
> 包装进程，否则 `destroy()` 会留下孤儿 shell（§8.18）。

必设环境变量（全部绝对路径）：

| 变量 | 值 |
| --- | --- |
| `PROOT_TMP_DIR` | `filesDir/proot_tmp`（**固定内部位置**，见 §8.14） |
| `PROOT_LOADER` | `nativeLibraryDir/libproot-loader.so` |
| `LD_LIBRARY_PATH` | `filesDir/native`（NativeDeps 释放的 libtalloc/libandroid-shmem；proot 内置 RUNPATH 指向 Termux 私有目录，其他设备不可用） |

guest 侧环境：`TERM=xterm-256color`、`HOME=/root`、`LANG=C.UTF-8`、`TMPDIR=/tmp`、guest `PATH`、`PROOTTERM_VERSION=<versionId>`。

**三层职责分离**（§8.13 是本项目最容易踩的架构约束）：

| 组件 | 负责 | 不负责 |
| --- | --- | --- |
| `ProotSession` | 单个进程 + 输出缓冲（主线程限定）、发命令、destroy | 不知道 Android 生命周期 |
| `SessionManager` | 进程内**多会话**注册表：`Map<id, SessionSlot>`，每槽独立状态机（IDLE/STARTING/RUNNING/STOPPING）+ 独立代次、观察者转发、命令写入入口 | 不启动服务、不建通知；**不提供**无参「当前会话」访问器 |
| `ProotService` | 前台服务：保活 + 常驻通知 + 承载启动流程（`startJobs: Map<id, Job>` 按实例跟踪）；**全部**会话结束后自动 `stopSelf` | 不直接持有 `ProotSession` |

- `redirectErrorStream(true)`；后台线程 `InputStreamReader(UTF_8)` 读取，`mainHandler.post` 回主线程
- **输出不写 AppLogger**：交互式命令会刷爆 1MB 日志并挤掉启动/失败信息；只记启动命令、环境变量、退出码
- 启动前 `NativeDeps.ensure()` 释放运行库；`prootBin.setExecutable(true)` 兜底
- 非 PTY 无提示符/无回显：UI 本地回显 `$ <cmd>`；README 提示 `sh -i`
- API 24：`isRunning` 用 `exitValue()`；destroy 后台线程低版本仅 `waitFor()`
- 启动流程**必须先进前台再 fork**：Android 8+ 要求 `startForegroundService` 后 5 秒内
  `startForeground`，而准备 rootfs 可能更慢，否则 ANR
- **外部存储执行限制**：`/Android/data` 在 Android 11+ 由 FUSE 提供、通常不允许执行
  文件，proot 可能报 permission denied。`ProotSession.explainStartFailure` 会把该错误
  翻译成「请把存储位置改为内部存储后重新安装」的可操作提示

### 4.5 存储位置（StorageLocation + AppPrefs + RootfsManager）

两个位置都是**应用私有沙箱**，都不需要任何存储权限：

| 位置 | 实际路径 | 获取方式 |
| --- | --- | --- |
| `INTERNAL` | `/data/data/<pkg>` | `context.filesDir` |
| `EXTERNAL` | `/Android/data/<pkg>` | `context.getExternalFilesDir(null)` |

- `RootfsManager` 每次构造时读 `AppPrefs.storageLocation` 并解析 `sandboxDir`；
  **外部位置不可用（未挂载 / getExternalFilesDir 返回 null）时自动降级到内部**
  并把 `locationFallback=true`，UI 必须如实提示，否则用户以为数据在外部
- 两个位置布局完全一致（`rootfs/`、`tmp_*`、`*.part`），切换只是换根目录；
  proot 的所有路径都是运行期拼接的绝对路径，不依赖编译期固定值
- **切换只影响后续安装**，不迁移已装数据（跨文件系统 `renameTo` 不可靠，
  逐文件复制几十万小文件既慢又可能中断）。切换时会先结束运行中的会话
- 版本列表对「装在另一个位置」的版本显示 `State.InstalledElsewhere`，
  按钮为「重新下载」而不是「启动」（后者会立刻报「尚未安装」）
- `PROOT_TMP_DIR` / `logs/` / `native/` **固定在内部**（§8.14）

### 4.6 开机自启动（BootReceiver + ProotService）

```
BOOT_COMPLETED / MY_PACKAGE_REPLACED
  → BootReceiver：只读 AppPrefs（autoStart + autoStartId），不做存在性判断
  → ProotService.launch(autoStartIntent)   # API26+ 用 startForegroundService
  → ProotService：读清单校验版本存在、校验 isInstalled、再 startForeground + fork
```

- 广播有 10 秒预算，且开机瞬间外部存储可能尚未挂载，因此**所有校验都放到服务里做**
- `BootReceiver` 不处理 `LOCKED_BOOT_COMPLETED`（那时应用目录仍处于加密锁定状态）
- 用户手动「强制停止」应用后，系统不会再投递 `BOOT_COMPLETED`，
  必须重新打开一次应用——这是系统限制，无法绕过
- 部分定制 ROM 还需在系统设置里额外允许「自启动」「后台运行」

### 4.7 UI（MainActivity，五屏 Screen 枚举）

- 结构：`DrawerLayout + NavigationView`，**默认「我的系统」**；顶栏 `MaterialToolbar` 汉堡开抽屉，副标题显示全局状态
  1. **我的系统**（Screen `MY_APPS`）`view_myapps.xml` + `MyAppAdapter`：扫描当前存储位置下所有**已安装**的 rootfs（判定 `.installed` 标记），显示系统名/shell/占用/「运行中」徽标，操作：启动 / 打开终端 / 卸载 / 详情。空列表时区分「一个都没装」与「装在另一个位置」并给一键切换
  2. **终端页**（Screen `TERMINAL`）`view_terminal.xml`：搜索框过滤输出 + 纯黑等宽终端 + **安全键盘规避的命令输入框**（`App.TerminalInput`，§8.15）+ 功能键两行（ESC/CTRL/ALT/TAB/回车/`|`/`/`/HOME/END/PGUP/PGDN + 方向键）+ CTRL/ALT 待命时展开的 a~z 组合键行
  3. **发行版管理**（Screen `DISTROS`）`view_distros.xml` + `DistroAdapter`：三家族卡片，点击进入版本页
  4. **版本选择**（Screen `VERSIONS`）`view_versions.xml` + `VersionAdapter`：下载-取消-启动-卸载，含 `State.InstalledElsewhere`；头部显示「将安装到：<当前存储路径>」
  5. **设置页**（Screen `SETTINGS`）`view_settings.xml`：**存储位置单选 + 开机自启动开关与目标选择**、版本/包名/ABI/SDK/rootfs 路径、GitHub 链接、清空终端
  - **nav_logs** 不占 Screen，直接 `startActivity(LogActivity::class.java)`
- 终端**返回键**：会话运行中弹「后台保持运行 / 结束会话」对话框；否则回「我的系统」页
- 图标：全部 vector drawable；应用图标 `ic_launcher.xml`（`>_` 深色圆角方块）
- 状态色：页面 `#F5F5F5`、强调粉 `#FFB6C1` / 浅蓝 `#ADD8E6`；状态栏 `windowLightStatusBar`
- 同时仅一个安装任务（闸门 `activeInstallId`）；**多个实例可同时运行**（§8.17），「我的系统」列表用 `MyAppAdapter.setRunningIds(Set<String>)` 一起打徽标；家族/版本卡片状态由 `DistroAdapter.setState` / `VersionAdapter.setState` 驱动
- 安装全包在 `Dispatchers.IO` + 全局 try-catch + 下载前 `RootfsManager` 空间检查（检查目标位置分区）；失败只 Toast 不闪退
- 终端：`outputRaw` 缓冲 + 搜索过滤渲染，超 200KB 截头并在顶部提示；自动滚底
- 状态文案走 `strings.xml`，不硬编码中文到 `setStatus`
- 作用域 `MainScope()`；`onDestroy` 只取消安装 job 与下载 Call，**不动会话**（§8.13）

## 5. 数据与文件布局

**内部位置**（`INTERNAL`，`context.filesDir` = `/data/data/<pkg>`）：

```
filesDir/
  rootfs/<id>/            # 最终 rootfs（含 .installed）
  rootfs/<id>.old/        # 升级安装的临时备份，成功后删除
  tmp_<id>/               # 解压临时目录
  <id>.<format>.part      # 断点续传（单线程）
  <id>.<format>.part.<N>  # 多线程分块（N=0..7），合并后删除
  <id>.<format>.part.<N>.src  # 分块来源标记（url|total），确保跨镜像不复用
  instances.json          # 每实例配置：资源限制 / 镜像偏好 / 线程数 / 换源记录（§8.17）
  native/                 # NativeDeps 释放的 libtalloc.so.2 等（固定内部）
  proot_tmp/              # PROOT_TMP_DIR（固定内部）
  logs/                   # AppLogger（固定内部）
```

**外部位置**（`EXTERNAL`，`context.getExternalFilesDir(null)` = `/Android/data/<pkg>/files`）：

```
filesDir/                 # 与内部同构，但只有 rootfs 相关部分会用到
  rootfs/<id>/            # 最终 rootfs（含 .installed）
  rootfs/<id>.old/
  tmp_<id>/
  <id>.<format>.part
```

`native/`、`proot_tmp/`、`logs/` **永远在内部**，不随存储位置变化（§8.14）。
因此「切换存储位置」实际只改变 `rootfs/`、`tmp_*`、`*.part` 三者的落点。

## 6. 清单格式（assets/rootfs_manifest.json）

顶层 `{"version": 2, "distros": [ { id/name/description/icon, versions: [...] } ]}`，版本字段均必填，`mirrors`/`sha256` 可为空：

`ManifestLoader` 会校验顶层 `version` 必须等于 `SUPPORTED_VERSION`（当前 2），否则抛 `IllegalArgumentException`；同时校验每个版本的 `format` 属于 `RootfsExtractor` 支持的集合（`tar.gz` / `tar.xz`），并拒绝空清单。清单结构升级时必须同步提高 `SUPPORTED_VERSION`。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | string | 家族 id（`alpine`/`debian`/`ubuntu`）；版本 id 如 `alpine-3.20` 作目录名 |
| name | string | 家族名 / 版本名 |
| description | string | 描述 |
| size | long | 预计字节（进度 UI 兜底，不参与完整性校验） |
| url | string | 主下载地址 |
| mirrors | string[] | 备用镜像（如 tuna） |
| sha256 | string | 小写十六进制；空串跳过校验 |
| format | string | `tar.gz` / `tar.xz` |
| defaultShell | string | guest shell 绝对路径 |

已知：Alpine 3.20/3.19/3.18（dl-cdn+sha256）、Debian 13/12/11（termux proot-distro tar.xz，sha256 空）、Ubuntu 24.04.5/22.04.5/20.04.5（cdimage arm64 tar.gz+sha256）。

## 7. 构建与验证

### 本地

```bash
# 1) PRoot 二进制已随仓库入库（app/src/main/jniLibs/arm64-v8a/*.so）
#    若需重新提取：见同目录 README.txt（Termux deb → 重命名放入）

# 2) 本地签名（可选，仅 assembleRelease 需要）
cat >> local.properties <<'EOF'
KEYSTORE_PATH=/abs/path/my.jks
KEYSTORE_PASSWORD=***
KEY_ALIAS=***
KEY_PASSWORD=***
EOF

# 3) 构建（wrapper 已入库，Gradle 8.13 自动下载）
./gradlew assembleDebug      # 调试包，不依赖签名
./gradlew assembleRelease    # 正式包，需签名四件套
```

### CI（.github/workflows/android.yml）

1. `push` 任意 **tag** → 构建 + 自动创建 GitHub Release（附 APK）
2. **workflow_dispatch** → 仅构建 + Artifact 上传，不发 Release
3. 步骤顺序：Checkout → JDK17 → Accept SDK Licenses → Decode Keystore → chmod gradlew → `./gradlew assembleRelease` → Upload → Release（**无 Fetch PRoot 步骤**，`jniLibs` 两个 .so 已入库直接打包）
4. 所需 Secrets：`KEYSTORE_BASE64`（jks 的 base64）、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`

### 终检清单

```bash
grep -r "com.example" app/src/main/java/            # 必须为空
grep -rn "java\.nio\|toPath()" app/src/main/java/   # 必须为空（§8.6）
grep -rn "READ_EXTERNAL_STORAGE\|WRITE_EXTERNAL_STORAGE\|MANAGE_EXTERNAL_STORAGE" \
     app/src/main/                                  # 必须为空（§8.2）
grep -n "textPassword\|textWebPassword" app/src/main/res/layout/view_terminal.xml
                                                    # 必须为空（§8.15）
grep -n "ACTION_OPEN_DOCUMENT_TREE" app/src/main/java/  # 必须为空（§8.2）
ls -l gradle/wrapper/                               # jar + properties 齐全
git remote -v                                       # 必须是 https://github.com/LiStudioorg/linuxandroid.git
test -f app/src/main/jniLibs/arm64-v8a/libproot.so && echo local-so-ok
./gradlew assembleDebug
```

R.id / R.string / R.layout / R.drawable 交叉校验（防止 `findViewById` 拼错导致运行期 NPE
或编译期 unresolved reference）：

```bash
cd app/src/main
# 代码用到但布局没定义的 id
comm -23 <(grep -rhoP 'R\.id\.\w+' java/ | sed 's/R\.id\.//' | sort -u) \
         <(grep -rhoP 'android:id="@\+id/\w+"' res/ | sed 's/.*@+id\///;s/"//' | sort -u)
# 定义了但没人用的 string（允许为空，非零说明有死文案）
comm -23 <(grep -rhoP '<string name="\w+"' res/values/strings.xml | sed 's/<string name="//;s/"//' | sort -u) \
         <({ grep -rhoP 'R\.string\.\w+' java/ | sed 's/R\.string\.//'; grep -rhoP '@string/\w+' res/ | sed 's/@string\///'; } | sort -u)
```

## 8. 硬性约束（禁止违反）

1. UI 使用 **AppCompat + Material Components**（DrawerLayout / NavigationView / RecyclerView / MaterialCardView / MaterialToolbar，浅色主题）；布局 XML + Kotlin，不引入 Compose。
2. **不申请任何存储权限**（`READ_/WRITE_EXTERNAL_STORAGE`、`MANAGE_EXTERNAL_STORAGE` 一律禁止），**不使用 SAF**（`ACTION_OPEN_DOCUMENT_TREE` 等）。rootfs 只能放在以下两个**应用私有沙箱**之一，两者的共同点是 Android 4.4 起无需权限即可读写：
   - 内部：`context.filesDir` = `/data/data/<pkg>`（设备上常为 `/data/user/0/<pkg>`）
   - 外部：`context.getExternalFilesDir(null)` = `/Android/data/<pkg>`
   不得读写这两个目录之外的任何路径。
3. Manifest 必须 `android:extractNativeLibs="true"`；Gradle 必须 `packaging { jniLibs { useLegacyPackaging = true } }`。（实现上由 Gradle DSL 触发 AGP 在合并清单中合成该属性，源码 Manifest 不写字面量，见 `docs/COMPLIANCE.md` §4.1。）
4. 仅 `arm64-v8a`。
5. 包名三处一致：`com.li63050a.linuxandroid`（namespace / applicationId / Kotlin package）。
6. 禁止使用 `java.nio.file`；`Process.isAlive/waitFor(timeout)/destroyForcibly` 必须 SDK_INT 分支。
7. 下载必须 `.part` + Range；解压必须先 tmp 再 rename；符号链接、权限位、路径穿越三者缺一不可。
8. `PROOT_TMP_DIR`、`PROOT_LOADER`、`LD_LIBRARY_PATH` 必须设置为绝对路径。
9. `jniLibs/**/*.so` **必须入库**（`.gitignore` 不得排除；`assets/native/**` 运行库也必须入库）。
10. 新增发行版/历史版本只改 `rootfs_manifest.json` 嵌套结构，不动下载/解压核心逻辑；删除自定义发行版 FAB 功能已废弃，禁止加回。
11. 远程仓库为 HTTPS：`https://github.com/LiStudioorg/linuxandroid.git`（协议变更需维护者确认）。
12. 日志分享必须 FileProvider `content://`，禁止 `file://`；闪退前堆栈必须先写 `AppLogger` 再结束进程。
13. **shell 进程不得由 Activity 持有**。会话的唯一所有者是 `SessionManager`（进程内多会话管理器）+ `ProotService`（前台服务）。Activity 只能 `attach`/`detach` 观察者。
    - `MainActivity.onDestroy` **禁止**调用 `SessionManager.stopAll()` / `stop(id)` 之类的任何终止调用；
    - `ProotService.onDestroy` **同样禁止**——`onDestroy` 不等于「用户要结束会话」，系统在内存紧张、OEM 后台清理、前台服务被回收时都会销毁服务，而这恰恰是前台服务要扛住的场景。在这里杀会话会让「后台保持运行」与「开机自启动」双双失效。
    - 会话的唯一权威结束点是 `ACTION_STOP`（通知栏「结束」按钮，可带 `EXTRA_INSTANCE_ID` 只结束一个实例）。
    - `SessionManager.start()` 必须为每个实例创建**独立**的 `SessionSlot`（含独立的代次计数器），并且**禁止**用一个全局字段持有"当前会话"——那正是多实例并存的头号反模式。旧单例时代的 `prepare()` 覆盖语义已由 per-slot 的 `AtomicInteger epoch` 取代。
    - 所有「fork 之后才能确定是否还需要」的启动路径，必须用**该实例自己的** `slot.epoch` 代次校验，防止用户在 fork 期间点「结束」后会话被复活。
    - 实例 id 即 rootfs 目录名（`DistroInfo.id`）。**同一个实例同时只允许一个运行中的 shell**；重复启动必须先结束该实例的旧会话。
14. **`PROOT_TMP_DIR`、`logs/`、`native/` 必须固定在内部 `filesDir`**，不跟随用户的存储位置选择。原因：proot 需要在这些位置执行临时文件，而外部位置在 Android 11+ 上由 FUSE 提供、通常不可执行；日志也必须放在最可靠的位置以便排查存储问题。
15. **命令输入框必须避开安全键盘**：使用 `style="@style/App.TerminalInput"`（`textVisiblePassword|textNoSuggestions`，`imeOptions=actionNone|flagNoExtractUi|flagNoFullscreen`，`importantForAutofill=no`）。禁止给该输入框使用 `textPassword`/`textWebPassword` 变体，也不要用 `actionSend`/`actionSearch` 等动作型 `imeOptions`。
16. 终端快捷键按钮发出的必须是**真实控制序列**（`ESC=\x1b`、`TAB=\x09`、方向键 `\x1b[A..D`、`CTRL+字母=0x01..0x1A`、`ALT+字母=\x1b+字母`），禁止把快捷键做成空壳按钮。

17. **多实例隔离**：每个实例（= 一个已安装的 rootfs，id 即目录名）拥有独立的 `InstanceConfig`（资源限制、镜像偏好、换源设置），持久化在 `instances.json`。**禁止**把实例级配置写成全局单例字段或全局 SharedPreferences 键——那会让「多实例」名存实亡。全局偏好（如界面语言、默认存储位置）仍然放 `AppPrefs`。
    - 每个实例独立的运行期状态：进程、输出缓冲、退出原因、资源限制。
    - `SessionManager` 内部用 `Map<String, SessionSlot>` 持有全部实例状态，`snapshot(id)` 按 id 取，**不得**再提供无参的「当前会话」语义（`sessionOrNull()` 式的全局访问器禁止新增）。

18. **资源限制必须真正下发到 guest**，禁止只存配置不生效。非 root Android 无法使用 cgroup，因此采用 ulimit 语义：
    - **必须把 `/bin/sh`、`-c`、脚本文本作为 3 个独立 argv 传给 proot**，禁止把整段脚本拼成单个字符串。proot **不会**替你调用 shell：它把最后一个参数当**可执行文件路径**解析（`which()` → `execvp`），单字符串会让它去找一个名字是 `ulimit -v …; exec …` 的文件，报 `'…' not found (root = …, $PATH=…)`，导致**凡是设了资源限制的会话一律启动失败**。用 `ResourceLimits.toGuestCommand()` 取 argv 列表。
    - 脚本内**必须**用 `exec` 让 shell 替换掉包装进程（否则多一层 sh，`destroy()` 只能杀掉包装进程而留下孤儿 shell）。
    - `-v`（虚拟内存 KB）/ `-u`（进程数）/ `-n`（文件描述符）为通用项；`-t`（CPU 秒）Semantics 上限制的是 CPU 时间而非「CPU 核数」，UI 文案必须如实说明，不得把它描述成「CPU 核数限制」。
    - 取值为 0 表示「不限制」，此时**不得**生成对应的 `ulimit` 语句。
    - 资源限制只影响新建会话；修改后需重启该实例的会话才生效，UI 必须提示。

19. **下载器的三条不可回退的可靠性约束**（在原有 `.part` + Range 之上）：
    - **多线程分块下载**：分块数与分块大小必须可配置且有上限；服务端**不支持** `Range`（返回 200）时，必须**自动降级**为单线程整文件下载，不得直接失败。
    - 分块临时文件命名 `<id>.<format>.part.<index>`，合并后删除；合并必须校验总长度等于 `Content-Length`，长度不符必须整份丢弃并报错（否则会解压损坏的归档）。
    - 取消下载时必须**清理所有分块临时文件**，不得残留。
    - `UA` 与 `Accept-Encoding: identity` 必须保留：identity 保证字节偏移与本地文件一一对应。

20. **镜像优选与自定义镜像源**：
    - 测速必须**有超时上限**且并发探测，禁止串行阻塞等待所有镜像；全部探测失败时必须回退到清单原始顺序，**不得**因为测速失败而导致无法下载。
    - 自定义镜像源必须持久化，且**只允许 http/https** 前缀；写入前必须做 scheme 校验，防止 `file://` 之类的意外输入。
    - 测速结果只是**排序建议**，不是信任来源：最终完整性仍然只由 SHA256（清单非空时）保证。

21. **APT 换源必须幂等且可逆**：
    - 换源前必须备份原 `sources.list` / `sources.list.d/*.sources`，备份文件带固定后缀，重复换源不得叠加破坏原备份。
    - 必须支持「恢复原始源」。找不到备份时必须如实报错，不得假装成功。
    - Debian/Ubuntu 的**新式 `.sources`（deb822）格式**与**旧式单行 `sources.list`** 都要处理；发行版代号（bookworm/trixie/noble/jammy…）必须从 rootfs 内 `/etc/os-release` 读取，**不得**硬编码猜测。
    - Alpine 使用 `/etc/apk/repositories`，换源规则不同，必须单独处理。
    - 换源写文件必须是**原子**的（写临时文件 + rename），避免中断留下半个文件导致 apt 完全不可用。


## 9. 文档维护

文档索引见 [`docs/README.md`](docs/README.md)。各文档职责**不重叠**：

| 文档 | 回答什么 | 读者 |
| --- | --- | --- |
| `README.md` | 这是什么、怎么装、怎么用 | 用户 |
| `AGENTS.md`（本文） | 必须遵守什么规则 | AI / 开发者 |
| `docs/CONTRIBUTING.md` | 我怎么开始干活 | 新贡献者 |
| `docs/ARCHITECTURE.md` | 系统怎么运作、为什么这么设计 | 开发者 |
| `docs/MODULES.md` | 这个类怎么用、有什么坑 | 改代码的人 |
| `docs/TESTING.md` | 怎么验证改动是对的 | 开发者 |
| `docs/TROUBLESHOOTING.md` | 出问题怎么查 | 用户 / 开发者 |
| `docs/COMPLIANCE.md` | 当前代码是否合规 | 维护者 |

维护约定：

1. **单一事实来源**：同一事实只在一处详述，其他地方链接过去，避免多份文档互相矛盾。
2. **改接口先改 `docs/MODULES.md`**；**改设计先改 `docs/ARCHITECTURE.md`**。
3. **约束以本文 §8 为准**。若代码与约束不符，要么改代码，要么显式修改约束并在 `docs/COMPLIANCE.md` 留档说明理由（参考 §8.3 `extractNativeLibs`、§8.11 远程协议两例的处置方式）。
4. **新增风险/待办**：设计层面的写进 `docs/ARCHITECTURE.md` §19；合规层面的写进 `docs/COMPLIANCE.md` §5。
5. **修改代码后同步更新受影响的文档**，尤其是类清单、行数、常量表、`docs/MODULES.md` 的 API 契约。

> ⚠️ **`docs/` 目录当前未被 git 跟踪**（untracked，且未被 `.gitignore` 忽略）。这意味着 `git clone` 的人拿不到这些文档。建议执行 `git add docs/` 纳入版本控制；若决定不纳入，请在 `.gitignore` 中显式写明原因。
