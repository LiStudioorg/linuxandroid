# 术语表

本文件解释 ProotTerm 开发与排障中会遇到的专有名词。

> 首次阅读建议顺序：[README.md](../README.md) → 本文件 → [ARCHITECTURE.md](ARCHITECTURE.md)。

---

## A. 核心概念

### PRoot

一个**用户态**实现，让程序以为自己运行在另一个根目录（`chroot` 的效果），
**不需要 root 权限**。

原理是通过 `ptrace` 拦截系统调用并改写其中的路径参数。因此：

- ✅ 免 root，普通应用就能跑 Linux rootfs；
- ❌ **性能有损耗**（每次系统调用都要陷入处理），比真 chroot 慢；
- ❌ **共享宿主内核**——不能跑不同的内核版本，也不能用需要内核模块的功能。

本项目用的是 [Termux](https://termux.dev) 适配过的 PRoot 二进制，重命名为
`libproot.so` 放进 `jniLibs/`（Android 只允许从 `nativeLibraryDir` 执行文件）。

> 相关约束：§8.8（环境变量必须绝对路径）、§8.9（二进制必须入库）

### rootfs

一个 Linux 发行版的**根文件系统**——即 `/bin`、`/etc`、`/usr` 等目录的集合。
本项目从网络下载打包好的 rootfs（`.tar.gz` / `.tar.xz`），解压到应用私有目录后
用 PRoot 启动其中的 shell。

一个 rootfs 在项目里就叫一个**实例**，其目录名即实例 id（如 `alpine-3.20`）。

### proot-distro

[Termux 的项目](https://github.com/termux/proot-distro)，提供各发行版预打包的
rootfs。本项目的 Debian 版本用的是它的产物（`tar.xz`）。

### guest / host

- **host（宿主）**：Android 系统本身。
- **guest（客体）**：PRoot 里跑的 Linux 环境。

说「guest 侧环境变量」指的是传给 PRoot 内 Linux 的变量
（`TERM`、`HOME`、`LANG`…），区别于传给 proot 进程本身的
（`PROOT_TMP_DIR`、`PROOT_LOADER`、`LD_LIBRARY_PATH`）。

---

## B. PRoot 参数与环境变量

本项目的启动命令形如：

```
libproot.so -r <rootfs> -0 -w /root -b /dev -b /proc -b /sys \
            -b /dev/urandom:/dev/random  <shell>
```

| 参数 | 含义 |
| --- | --- |
| `-r <path>` | **rootfs 路径**，即 guest 眼中的 `/` |
| `-0` | 伪装成 **root（uid 0）**，否则解压出来的文件权限会让普通用户无法访问 |
| `-w <path>` | 初始**工作目录**（guest 内路径） |
| `-b <path>` | **bind**，把宿主路径挂进 guest。`-b /dev/urandom:/dev/random` 是「宿主地址:客体地址」形式 |

必设的环境变量：

| 变量 | 值 | 为什么 |
| --- | --- | --- |
| `PROOT_TMP_DIR` | `filesDir/proot_tmp` | proot 需要可执行临时文件的位置。**固定内部**（§8.14），因为外部存储在 Android 11+ 通常不可执行 |
| `PROOT_LOADER` | `nativeLibraryDir/libproot-loader.so` | proot 的加载器，必须绝对路径 |
| `LD_LIBRARY_PATH` | `filesDir/native` | proot 依赖 `libtalloc.so.2` / `libandroid-shmem.so`；其内置 RUNPATH 指向 Termux 私有目录，在其他设备上不存在 |

> ⚠️ **proot 不会替你调用 shell。** 它把最后一个参数当作**可执行文件路径**解析
> （`which()` → `execvp`）。所以想执行一段脚本，必须传 **3 个独立 argv**：
> `/bin/sh`、`-c`、脚本文本。拼成单个字符串会让 proot 去找一个名字是
> `ulimit -v …; exec …` 的文件。
> 相关约束：§8.18

---

## C. 资源限制（ulimit）

`ulimit` 是 POSIX shell 内建命令，用来限制进程可用资源。**非 root 的 Android
无法使用 cgroup**（需要特权），所以本项目采用 ulimit 语义。

| 项目 | 对应 | 单位 | 注意 |
| --- | --- | --- | --- |
| 虚拟内存 | `ulimit -v` | MB（内部转 KB） | 限的是**地址空间**而非物理内存。设太小（如 64MB）会让 shell 直接起不来 |
| 栈 | `ulimit -s` | MB（内部转 KB） | — |
| 进程数 | `ulimit -u` | 个 | **同时限制线程数**。有些程序一启动就要几十个线程 |
| 文件描述符 | `ulimit -n` | 个 | 太小会报 `Too many open files` |
| CPU 时间 | `ulimit -t` | **秒** | 限的是**累计 CPU 时间**，不是「几个核」，也不是限速 |

取值为 `0` 表示**不限制**，此时不生成对应语句。

**限制在会话启动时固定，无法从外部修改**——改配置后必须重启该实例的会话。

为什么脚本末尾有 `exec`：让真 shell **替换**掉包装用的 `sh` 进程。
否则会多一层父子进程，`destroy()` 只能杀掉包装进程，留下孤儿 shell。

---

## D. 下载与镜像

| 术语 | 含义 |
| --- | --- |
| `.part` | 下载中的临时文件。完成后才 rename 成正式文件，避免半个文件被当成完整包 |
| **Range 续传** | HTTP `Range: bytes=<n>-` 请求，从第 n 字节继续下载。断网重来不用从头开始 |
| **206 / 200** | `206 Partial Content` = 服务端支持 Range；`200 OK` = **忽略**了 Range，返回整个文件。本项目遇到 200 会**降级为单线程**（§8.19） |
| **镜像（mirror）** | 同一文件的另一个下载地址。清单里 `url` 是主地址，`mirrors[]` 是备用 |
| **测速优选** | 并发请求各镜像的一小段，按延迟排序。**只是排序建议**，不是信任来源（§8.20） |
| **分块并发下载** | 把大文件切成 N 段并行下载再合并。本项目 ≥8MB 且线程数 >1 时启用，上限 8 线程 |
| **`.src` 来源标记** | 分块文件旁的标记，记录 `url\|total`。复用分块时必须**长度 + 标记**双匹配——镜像顺序按延迟排序且不稳定，仅凭长度会把别的镜像的同长度块拼进来（§8.19） |
| **`Accept-Encoding: identity`** | 禁用压缩。否则服务端压缩后字节偏移与本地文件对不上，Range 续传会错位 |
| **SHA256** | 清单里非空时用于校验完整性。**Debian/Ubuntu 的清单该项为空**，所以下载器的可靠性不能只依赖它 |

---

## E. APT 换源

| 术语 | 含义 |
| --- | --- |
| **APT** | Debian / Ubuntu 的包管理器（`apt install`） |
| **源（sources）** | APT 从哪里下载包。默认是官方源，国内访问慢，通常换成清华 / 中科大 / 阿里 / 华为 |
| **deb822** | 新式源文件格式（`/etc/apt/sources.list.d/*.sources`），形如多行 `Key: Value` |
| **legacy sources.list** | 旧式单行格式（`/etc/apt/sources.list`），形如 `deb http://… bookworm main` |
| **codename（发行版代号）** | 如 `bookworm`（Debian 12）、`noble`（Ubuntu 24.04）。本项目从 rootfs 内 `/etc/os-release` **读取**，不硬编码猜测（§8.21） |
| **debian-ports / ubuntu-ports** | ⚠️ **arm64 专用路径**。Debian 的 arm64 在 `debian-ports` 而非 `debian`，Ubuntu 同理。用错会让 `apt update` 返回 **404** —— 这是换源最常见的失败原因 |
| **APK / apk** | ⚠️ **两个不同含义**：① Alpine 的包管理器（小写，`/etc/apk/repositories`）；② Android 安装包格式（`.apk` 文件）。本项目中两者都会出现，靠上下文区分 |
| **Alpine** | 使用 `apk` 而非 `apt`，仓库配置在 `/etc/apk/repositories`，换源规则完全不同，必须单独处理 |

---

## F. Android 平台

### 应用私有目录

| 术语 | 路径 | 说明 |
| --- | --- | --- |
| **内部存储** | `/data/data/<pkg>`（`context.filesDir`） | 一定可用，速度快，不占用户可见空间 |
| **外部存储（应用私有）** | `/Android/data/<pkg>`（`context.getExternalFilesDir(null)`） | 空间通常更大，**但 Android 11+ 由 FUSE 提供、一般不可执行**，proot 可能起不来 |

⚠️ 二者都**不需要任何存储权限**（Android 4.4 起即如此）。本项目
**不申请** `READ_/WRITE_EXTERNAL_STORAGE`，**也不用 SAF**
（`ACTION_OPEN_DOCUMENT_TREE`）——相关约束：§8.2。

### 其他 Android 术语

| 术语 | 含义 |
| --- | --- |
| **minSdk 24** | 最低支持 Android 7.0。很多 26+ 的 API 必须用 `SDK_INT` 分支保护（§8.6） |
| **ABI** | 应用二进制接口。本项目只支持 **`arm64-v8a`** |
| **jniLibs** | Gradle 约定的原生库目录。Android 只允许从 `nativeLibraryDir` 执行文件，所以PRoot 二进制必须放这里并重命名为 `lib*.so` |
| **nativeLibraryDir** | 运行期 `jniLibs` 的落地路径，供拼 `PROOT_LOADER` 用 |
| **useLegacyPackaging** | 让原生库**解压**到 `nativeLibraryDir` 而不是直接从 APK 内存映射。proot 必须解压后才能执行（§8.3） |
| **FileProvider** | Android 7+ 禁止用 `file://` 分享文件，须用 `content://`（§8.12） |
| **前台服务** | 带常驻通知的服务。本项目用 `ProotService` 保活 shell，退到后台仍继续执行 |
| **`startForegroundService`** | Android 8+ 要求启动后 **5 秒内**必须调用 `startForeground`，否则 ANR。因此本项目**先进前台再 fork** |
| **ACTION_STOP** | 结束会话的唯一权威入口（通知栏「结束」按钮）。**`onDestroy` 绝不能杀会话**（§8.13） |
| **FUSE** | Android 11+ 用 FUSE 实现 `/Android/data`，通常不允许执行文件 |
| **安全键盘** | 系统对密码输入框（`textPassword`）强制启用的防截屏键盘。终端命令框**必须避开**它，用 `textVisiblePassword`（§8.15） |
| **BOOT_COMPLETED** | 开机广播。本项目用它做开机自启动 |
| **强制停止** | 用户在设置里「强制停止」应用后，系统不再投递 `BOOT_COMPLETED`，自启动失效，必须重新打开一次应用 |

---

## G. 本项目内部概念

| 术语 | 含义 |
| --- | --- |
| **实例（instance）** | 一个已安装的 rootfs。**id 即目录名**（如 `alpine-3.20`）。每实例有独立配置、会话、资源限制（§8.17） |
| **`SessionManager`** | 进程内的**多会话注册表**（`Map<id, SessionSlot>`）。取代了早期的 `SessionHolder` 单例 |
| **`SessionSlot`** | 单个实例的运行期状态：状态机、独立代次（`epoch`）、进程、输出缓冲、退出原因 |
| **`epoch`（代次）** | 每槽一个的计数器。启动流程在 fork 前后各校验一次，防止用户在 fork 期间点「结束」后会话**被复活** |
| **`ProotSession`** | 单个 shell 进程 + 输出缓冲。**不知道 Android 生命周期** |
| **`ProotService`** | 前台服务，会话保活 + 常驻通知。承载启动流程 |
| **`InstanceStore`** | 每实例配置的持久化（`filesDir/instances.json`）。**进程内单例**，UI 与服务必须共用同一份 |
| **`focusedId`** | 「终端页当前**显示**哪个实例」。**纯视图状态**——改它绝不影响任何进程的启停 |
| **`.installed`** | 安装完成标记文件。扫描已装系统时判定它是否存在 |
| **原子安装** | 先解压到 `tmp_<id>`，再 rename 到 `rootfs/<id>`。失败时把旧的 `<id>.old` 挪回，**旧安装不丢** |
| **`State.InstalledElsewhere`** | 版本装在**另一个**存储位置时的状态。按钮显示「重新下载」而非「启动」 |
| **ABORTED** | `startBlocking` 的返回值之一，表示被取消且**没有启动任何东西**。**不可当作成功** |
| **§8.x** | 指 [AGENTS.md](../AGENTS.md) §8 的第 x 条硬性约束。代码注释与文档里大量以此交叉引用 |

---

## H. 工具链

| 术语 | 含义 |
| --- | --- |
| **AGP** | Android Gradle Plugin，版本 8.11.1 |
| **Gradle wrapper** | `gradlew` + `gradle/wrapper/`。固定 Gradle 版本（本项目 8.13），**必须入库** |
| **AAPT2** | Android 资源打包工具。资源引用错误（如用了不存在的属性）在这一步报错 |
| **deb822 / AAPT** | 前者是 APT 源格式，后者是 Android 资源工具，**完全无关**，仅名字里都有 "apt" |
| **Artifact** | GitHub Actions 的构建产物，保留 30 天 |
| **Release** | GitHub 的发行页面，附 APK 供用户下载。由 tag 或手动触发创建 |
| **prerelease** | 预发布标记。手动触发创建的 Release 自动带此标记 |
| **Secret** | GitHub 仓库的加密变量，用于签名口令等 |

---

## 相关文档

| 文档 | 内容 |
| --- | --- |
| [ARCHITECTURE.md](ARCHITECTURE.md) | 这些概念如何组装成系统 |
| [MODULES.md](MODULES.md) | 每个类的 API 契约 |
| [TROUBLESHOOTING.md](TROUBLESHOOTING.md) | 出问题按症状查 |
| [CONTRIBUTING.md](CONTRIBUTING.md) | 环境准备与代码地图 |
| [AGENTS.md](../AGENTS.md) §8 | 21 条硬性约束 |
