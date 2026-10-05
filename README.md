# LinuxAndroid（ProotTerm）— 免 root 的 Android Linux 终端

在 Android 应用内运行无图形界面的 Linux 环境：基于 **PRoot（Termux 适配版）**，通过简易文本终端输入命令、查看输出。无需 root，不申请存储权限，全部数据位于应用私有目录。

- 包名 / applicationId：`com.li63050a.linuxandroid`
- 版本：`0.0.0.1`（versionCode 1）
- 支持系统：**Android 7.0（API 24）～ Android 16（API 36）**
- 仅支持 **arm64-v8a（64 位 ARM）**

## 功能

- 现代化浅色 Material 界面：**侧边栏抽屉**导航（我的系统 / 终端 / 发行版 / 日志 / 设置），默认进入**我的系统**
- **可同时安装多个系统**：Alpine / Debian / Ubuntu 任意多个版本并存，「我的系统」页统一启动、打开终端、卸载、看占用
- **多个系统可同时运行**：每个系统一个独立实例，各有自己的会话、终端输出与资源配置，互不干扰；通知栏显示「ProotTerm：N 个实例运行中」，可单独结束某一个
- **资源限制**：每个实例可单独限制虚拟内存 / 进程数 / 文件描述符 / 栈 / CPU 时间（`ulimit` 语义，免 root 下 cgroup 不可用）；提供 4 组预设，也可手工填。保存后重启该实例的会话生效
- **镜像优选**：并发测速所有候选镜像并自动选最快的；全部超时则回退清单顺序（**测速失败绝不影响下载**）；也可自己填一个镜像源（仅允许 http/https）
- **多线程分块下载**：大文件按 4~8 线程并发分块下载（可调），支持断点续传；服务端不支持 `Range` 时自动降级单线程
- **一键 APT 换源**：为 Debian / Ubuntu / Alpine 切换到国内软件源，自动识别发行版代号与 arm64 专用路径，可一键**恢复原始源**（换源前自动备份，且不会覆盖上次的备份）
- **三发行版家族 + 多历史版本**：Alpine 3.20/3.19/3.18、Debian 13/12/11、Ubuntu 24.04/22.04/20.04，选家族 → 选版本下载
- **存储位置可选**：内部 `/data/data/<包名>` 或外部 `/Android/data/<包名>`，**两者都不需要任何存储权限**，设置页随时切换
- **会话后台保活**：shell 由前台服务承载，退到桌面/切到别的应用都继续执行；通知栏常驻「结束」按钮
- **开机自启动**：设置里选一个系统，开机后自动把它的 shell 跑起来
- **与手机同一个 IP**：PRoot 不隔离网络——guest 直接使用 Android 内核的网络栈，因此 `ip addr`、联网请求和手机完全一致，无需额外配置
- 终端页：搜索框过滤输出、**纯黑等宽终端**（自动滚底）、**两行功能键**（ESC / CTRL / ALT / TAB / 回车 / `|` / `/` / HOME / END / PGUP / PGDN / 方向键）+ CTRL、ALT 待命时展开的 **a~z 组合键行**
- **不弹安全键盘**：命令输入框使用 `textVisiblePassword` 而非密码框，输入法正常联想、不会切成安全键盘
- 终端返回键：会话运行中弹「后台保持运行 / 结束会话」
- 一键下载，实时进度；**断点续传**（`.part` + HTTP Range）；**多镜像自动回退**；大文件多线程分块并发
- SHA256 校验（清单有值才校验）
- 解压保留符号链接与 rwx 权限位，防路径穿越；先临时目录、成功后**原子重命名**再写 `.installed`
- **全局日志**（AppLogger）：内存缓冲 + 文件落盘，闪退堆栈自动写入；日志页可刷新/清空/复制/**一键分享**（FileProvider）
- 设置页含存储位置、开机自启动、版本信息与 GitHub 仓库链接

## 文档导航

| 我想…… | 看这里 |
| --- | --- |
| 了解全部文档 | [docs/README.md](docs/README.md)（文档索引） |
| 参与开发 | [docs/CONTRIBUTING.md](docs/CONTRIBUTING.md)（环境准备、代码地图、提交自检） |
| 理解架构 | [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)（分层、六条核心链路、设计决策） |
| 改某个类 | [docs/MODULES.md](docs/MODULES.md)（逐类 API 契约与坑） |
| 验证改动 | [docs/TESTING.md](docs/TESTING.md)（手工测试矩阵、回归清单） |
| 排查故障 | [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md)（按症状索引） |
| 了解编码约束 | [AGENTS.md](AGENTS.md)（21 条硬性约束） |

> ⚠️ 遇到「点启动没反应」，请**先确认设备是 arm64**（本项目仅支持 arm64-v8a，x86 模拟器上 PRoot 无法执行）。详见 [故障排查 §2.1](docs/TROUBLESHOOTING.md)。

## PRoot 二进制从哪来

**已随仓库入库**：`app/src/main/jniLibs/arm64-v8a/libproot.so` 与 `libproot-loader.so` 已提交（`.gitignore` 不排除），CI 直接打包，无需再下载。运行期依赖 `libtalloc.so.2`、`libandroid-shmem.so` 见下文，亦已入库。

如需重新从 Termux deb 提取（兜底）：

1. 下载最新 `proot_*_aarch64.deb`
   （仓库：<https://packages.termux.dev/apt/termux-main/pool/main/p/proot/>）
2. `dpkg-deb -x` 解开（或 7-Zip / `ar`）
3. 拷贝两份文件到 `app/src/main/jniLibs/arm64-v8a/`：
   - `data/data/com.termux/files/usr/bin/proot` → **`libproot.so`**
   - `data/data/com.termux/files/usr/libexec/proot/loader` → **`libproot-loader.so`**
     （个别旧版本在 `usr/lib/proot-loader`，以 deb 实际内容为准）
4. `chmod 755`

### 运行期依赖（已入库，无需操作）

`libproot.so` 还依赖 `libtalloc.so.2`、`libandroid-shmem.so`（proot 内置 RUNPATH 指向 Termux 私有目录，其他手机上无效）。二者已放在 `app/src/main/assets/native/arm64-v8a/`，首次启动 shell 时自动释放到应用私有目录，并通过 `LD_LIBRARY_PATH` 注入。

## 构建

要求：JDK 17；Gradle 用仓库自带 wrapper（8.13，首次自动下载）。

```bash
# 调试包（不依赖签名）
./gradlew assembleDebug

# 正式包（需要签名，见下）
./gradlew assembleRelease
```

或用 Android Studio 直接打开工程 Run / Generate Signed APK。

### 本地正式签名

在仓库根目录 `local.properties`（已 gitignore）追加：

```properties
KEYSTORE_PATH=/绝对路径/my.jks
KEYSTORE_PASSWORD=密码
KEY_ALIAS=别名
KEY_PASSWORD=密钥密码
```

优先级：环境变量（CI 注入）> `local.properties` > 兜底 `myapp.jks`（缺失则 Release 签名失败）。

## GitHub Actions（.github/workflows/android.yml）

| 触发 | 行为 |
| --- | --- |
| 推送任意 **tag** | 构建 Release APK → 上传 Artifact → **自动创建 GitHub Release** |
| 网页 **Run workflow** | 构建并上传 Artifact，不发 Release |

需配置的 **Secrets**：`KEYSTORE_BASE64`（jks 文件 base64）、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`。

生成 base64：

```bash
base64 -w0 my.jks   # macOS: base64 -i my.jks -o -
```

## Git 远程（HTTPS）

已绑定：

```bash
git remote -v
# origin  https://github.com/LiStudioorg/linuxandroid.git (fetch/push)
```

推送：`git push origin main`（远程为 HTTPS `https://github.com/LiStudioorg/linuxandroid.git`，需要 PAT 或凭据助手）。

## 使用方法

1. **先选存储位置**（可选）：侧边栏「设置」→「存储位置」选**内部存储**或**外部存储**。
   两者都免权限；不确定就保持默认的**内部存储**（兼容性最好，见下方注意事项）
2. 侧边栏「发行版」：点家族卡片进入**版本选择**，选版本点“下载” → 进度显示（可中断，再点**断点续传**，主源失败自动换镜像）
3. 校验 SHA256 → 解压 → 按钮变“启动”
4. 点击“启动”进入「终端」页；输入命令（如 `ls`、`cat /etc/os-release`）点“发送”或键盘发送键
5. 快捷键：ESC / CTRL（+字母）/ ALT（+字母）/ TAB / 回车 / 方向键 / HOME / END / PGUP / PGDN；
   搜索框过滤输出
6. 按返回键：会话运行中弹「后台保持运行 / 结束会话」。**选「后台保持运行」后，退回桌面或切换应用都不会中断 shell**
7. 「我的系统」页可同时管理多个已装系统：点「启动」切换、点「打开终端」回到当前会话
8. 「设置」→「开机自启动」：打开开关并选一个系统，之后每次开机自动跑起它的 shell

```sh
cat /etc/os-release     # 查看发行版
ip addr                 # 看到的就是手机自己的 IP —— PRoot 不做网络隔离
sh -i                   # 无提示符时进入交互 shell
apk update && apk add vim        # Alpine
apt update && apt install -y vim # Debian / Ubuntu
exit
```

### 关于存储位置

| | 内部存储 | 外部存储 |
| --- | --- | --- |
| 路径 | `/data/data/<包名>` | `/Android/data/<包名>` |
| 需要权限 | **不需要** | **不需要** |
| 占用手机内置空间 | 是 | 是（系统通常也把内置分区当“外部存储”） |
| PRoot 能否运行 | ✅ 稳定 | ⚠️ Android 11+ 上常因分区不可执行而失败 |

> ⚠️ **Android 11 及以上，外部存储位置可能无法启动系统**。原因：`/Android/data` 由 FUSE
> 提供、不允许执行文件，而 PRoot 需要执行 rootfs 里的程序。若启动报「权限不足」，
> 应用会提示你切回内部存储并**重新安装**该系统。
>
> 两个位置都属于应用私有沙箱，**卸载应用时都会被系统清除**——它们解决的是容量，不是持久性。

### 关于同时运行多个系统

每个已安装的系统都是**独立实例**，可以同时跑。例如一边用 Debian 编译、一边在 Alpine 里查资料：

- 「我的系统」页每个运行中的系统都有「运行中」徽标；
- 终端页**一次显示一个**实例（点「打开终端」即切换焦点）。切换焦点**不会**影响任何实例的运行；
- 通知栏显示「ProotTerm：N 个实例运行中」，点通知回到终端，点「结束」可停掉当前实例；
- 想只停某一个，在该系统的详情里点「卸载」（会先停它）或从终端切到它再按「结束会话」。

> ⚠️ 同时跑多个系统会成倍占用内存和 CPU。低端机型建议配合下面的「资源限制」使用。

### 关于资源限制

在「我的系统」→ 某个系统的**详情** → 「实例设置」里可以限制：

| 项目 | 说明 |
| --- | --- |
| 虚拟内存 | `ulimit -v`，单位 MB。**注意限的是"地址空间"不是"物理内存"**，设太小（比如 64MB）会导致 shell 直接起不来 |
| 进程数 | `ulimit -u`，同时限制线程数。某些程序一启动就要几十个线程 |
| 文件描述符 | `ulimit -n`，太小会让网络程序报 `Too many open files` |
| 栈 | `ulimit -s`，单位 MB |
| CPU 时间 | `ulimit -t`，单位**秒**。限的是累计 CPU 时间，**不是**「用几个核」，也不是限速 |

填 `0` 表示**不限制**（默认全部不限制）。改完**需要重启该实例的会话**才生效——已运行的 shell 的限制在启动时就固定了，无法从外部修改。

也可以直接点「预设」用现成组合（轻量 / 标准 / 开发 / 严格）。

### 关于镜像

下载前如果开启了「自动优选镜像」，应用会**并发测速**所有候选地址，挑最快的先下：

- 单个镜像最多等 4 秒，整批最多 6 秒；
- **如果全部测速失败，会退回清单里的原始顺序**——测速不会成为下载的阻碍；
- 也可以在「实例设置」里自己填一个镜像源（只支持 `http://` 或 `https://`）；
- 大文件（≥8MB）会按 4~8 个线程并发分块下载，可调；服务端不支持 `Range` 时自动退回单线程。

### 关于换源

在某个系统的「实例设置」里点「切换软件源」，可以选择清华 / 中科大 / 阿里 / 华为：

- 自动识别发行版代号（bookworm / noble / jammy …）；
- 自动处理 **arm64 专用路径**——Debian arm64 在 `debian-ports`、Ubuntu arm64 在 `ubuntu-ports`，用错会 `apt update` 404；
- 换源前自动备份原文件，**重复换源不会覆盖第一次的备份**，所以「恢复原始源」始终有效；
- 如果该系统正在运行，换源后需要**重启会话**（或在里面重跑 `apt update`）才生效。

### 关于开机自启动

- 需要 Android 授予通知权限与前台服务权限（首次启动会话时会请求）
- **必须先在应用里手动启动过一次**该系统（或至少装好它），否则开机时无可启动的目标
- 如果你在系统设置里对应用点了「强制停止」，Android 在那之后不会再送开机广播，
  需要重新打开一次应用——这是系统限制，无法绕过
- 小米 / 华为 / OPPO / vivo 等定制系统需额外在「自启动管理」「后台运行」里放行本应用

## 常见问题

| 现象 | 处理 |
| --- | --- |
| 无提示符、输入不回显 | 非 PTY 属正常，命令已执行；输入 `sh -i` / `bash -i` |
| 启动提示“缺少 PRoot” | 按上文放入两个 `.so` 后重新打包 |
| 点发送无反应 / 秒退 | 多半是 APK 缺 `libproot.so` 或缺运行库，检查 `jniLibs`/`assets/native` 后重新打包；完整堆栈见「日志」页 |
| 启动报「权限不足 / Permission denied」 | 存储位置选成了外部存储。去「设置」改回**内部存储**，再重新安装该系统 |
| 装了系统却看不到 | 你在「设置」里切换过存储位置。切回去即可，或在原位置重装 |
| 开机没有自动启动 | 先确认应用里自启动已开启并选好系统；再到系统设置里允许本应用自启动/后台运行 |
| 通知栏一直有一条通知 | 这是会话常驻通知，属正常；点它会回到终端，点「结束」会终止 shell |
| 返回键后命令还在跑 | 这是预期行为（前台服务保活）。想停就在通知栏点「结束」 |
| 终端里输入密码时弹出安全键盘 | 本项目已规避；若仍出现，说明你的输入法强制了安全键盘，请换用系统输入法 |
| 下载中断 | 再点“下载”，`.part` 自动续传 |
| SHA256 失败 | 自动删除坏文件，重新下载即可 |
| 空间不足 | 下载+解压建议预留 500MB |

## 工作原理（简述）

```
assets/rootfs_manifest.json
  → OkHttp Range 下载至 <沙箱>/<id>.<format>.part（多镜像回退）
  → SHA256 校验
  → Commons Compress 解压至 <沙箱>/tmp_<id>（符号链接/权限/防穿越）
  → 旧目录备份为 <id>.old，rename 为 <沙箱>/rootfs/<id>，写 .installed（失败可回滚）
  → NativeDeps 释放 libtalloc/libandroid-shmem → filesDir/native（固定内部）
  → ProotService（前台服务）先 startForeground，再 fork：
      libproot.so -r <rootfs> -0 -w /root -b /dev -b /proc -b /sys \
        -b /dev/urandom:/dev/random <shell>
      （PROOT_TMP_DIR / PROOT_LOADER / LD_LIBRARY_PATH 全部绝对路径）
  → SessionManager 持有进程与输出缓冲，Activity 只做观察者
```

`<沙箱>` 由「设置」里的存储位置决定（`filesDir` 或 `getExternalFilesDir`）；
`native/`、`proot_tmp/`、`logs/` 始终固定在内部 `filesDir`。

编码约定与完整方案见 [AGENTS.md](AGENTS.md)。

## 声明

PRoot 版权归其原作者；各发行版 rootfs 遵循各自许可证。本项目代码仅供学习与研究。
