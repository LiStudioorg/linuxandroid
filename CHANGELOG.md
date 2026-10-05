# 变更日志

本文件记录 ProotTerm（LinuxAndroid）每个版本的**用户可见变化**与**破坏性变更**。

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循[语义化版本](https://semver.org/lang/zh-CN/)。

> ⚠️ **本项目当前处于 `0.0.x` 阶段，尚未发布稳定版。**
> 在 `1.0.0` 之前，`MINOR` 版本也可能包含破坏性变更（尤其是 rootfs 目录布局与
> `instances.json` 结构），升级前请留意本文件的「破坏性变更」小节。

---

## [未发布]

### 待处理

- **版本号尚未提升**：`app/build.gradle.kts` 仍为 `versionCode 1` /
  `versionName "0.0.0.1"`，但 `v0.0.0.1` ~ `v0.0.0.3` 三个 tag 已存在。
  下一个正式 tag 之前必须先提升版本号，否则新包无法覆盖安装旧包
  （`versionCode` 相同会被系统拒绝），且 Release 里的 APK 文件名会与旧包混淆。
  详见 [RELEASE.md](docs/RELEASE.md)。

### 已知限制（非缺陷，属平台约束）

- 外部存储位置（`/Android/data/<pkg>`）在 Android 11+ 由 FUSE 提供，
  通常**不允许执行文件**，proot 可能启动失败。已通过
  `ProotSession.explainStartFailure()` 给出「请改用内部存储」的可操作提示。
- 用户手动「强制停止」应用后，系统不再投递 `BOOT_COMPLETED`，
  开机自启动会失效，必须重新打开一次应用。这是 Android 平台限制。
- 部分定制 ROM（MIUI / EMUI / ColorOS 等）需要额外授予「自启动」「后台运行」权限。

---

## 发布状态说明

| 分支 | 自动构建 | 自动发行 |
| --- | --- | --- |
| `beta` | ✅ | ❌ 仅上传 Artifact |
| `main` | ❌ 未配置 | ❌ |
| tag `v*` | ✅ | ✅ 正式 Release |

功能开发在 `beta` 分支上集成；**尚未合并回 `main`**。

---

## beta（未打 tag，2026-10）

这一批包含四项新功能，以及由真实编译暴露并修复的三个缺陷。

### 新增

**多实例隔离管理**

- 多个系统可**同时运行**，每个实例拥有独立的会话、输出缓冲、退出原因与资源配置。
- 新增 `SessionManager`（多会话注册表），删除原 `SessionHolder` 单例。
- 新增 `InstanceConfig` / `InstanceStore`：每实例配置持久化到
  `filesDir/instances.json`（资源限制 / 镜像偏好 / 下载线程数 / 换源记录）。
- 「我的系统」页为运行中的实例显示「运行中」徽标；通知栏显示
  「ProotTerm：N 个实例运行中」，可单独结束某一个实例。

**资源限制（ulimit 语义）**

- 每实例可单独限制**虚拟内存 / 进程数 / 文件描述符 / 栈 / CPU 时间**，
  提供 4 组预设，也可手工填写。`0` 表示不限制。
- 免 root 下 cgroup 不可用，因此采用 `ulimit` 语义——限的是**地址空间**而非物理内存。
- 改后需**重启该实例的会话**才生效（已运行的 shell 无法从外部改限制）。

**镜像下载增强**

- **自动优选**：并发测速所有候选镜像并按延迟重排，单镜像 4s / 整体 6s 超时；
  全部失败时回退清单顺序，**测速失败不会阻断下载**。
- **自定义镜像源**：可自行填写，仅允许 `http://` / `https://`。
- **多线程分块并发下载**：≥8MB 的文件按 4~8 线程分块下载，支持断点续传；
  服务端不支持 `Range`（返回 200）时自动降级单线程。

**APT 源自动换源**

- 一键为 Debian / Ubuntu / Alpine 切换国内软件源（清华 / 中科大 / 阿里 / 华为）。
- 自动从 `/etc/os-release` 读取发行版代号，自动处理 arm64 专用路径
  （Debian 用 `debian-ports`、Ubuntu 用 `ubuntu-ports`）。
- 支持一键**恢复原始源**；换源前自动备份，重复换源不覆盖首次备份。

### 修复

以下缺陷均由**首次真实编译**（GitHub Actions）暴露，此前的静态检查未能发现：

- **AAPT 链接失败**：`App.TerminalInput` 样式里写了 `android:autoCorrect`，
  该属性**不是 Android 框架属性**（仅存在于 IDE 建议列表，`android.jar` 中无定义），
  导致 `:app:processReleaseResources` 报
  `style attribute 'android:attr/autoCorrect' not found`。
  禁用联想的实际需求已由 `inputType` 中的 `textNoSuggestions` 覆盖，故直接移除。
- **`AptSourceSwitcher.writeAtomic` 编译失败**：表达式体函数内使用了 `return`，
  Kotlin 报 `Returns are prohibited for functions with an expression body`。
  改为块体并补齐 `catch` 块闭合。
- **`MyAppAdapter.setRunningIds` 编译失败**：`Set.symmetricDifference` 是
  Kotlin 1.9+ 实验性 API，在 2.0.21 下解析歧义，报 `Unresolved reference` 与
  `Method 'iterator()' is ambiguous`。改为手写 `(old - new) + (new - old)`。

另外修复了在代码审查中发现、但尚未产生用户可见后果的严重缺陷：

- **设了资源限制的会话一律无法启动**：资源限制脚本被当作**单个 argv** 传给 proot。
  proot 不会替调用 shell——它把最后一个参数当作**可执行文件路径**解析
  （`which()` → `execvp`），于是报 `'…' not found (root = …, $PATH=…)`。
  已改为传 3 个独立 argv（`/bin/sh`、`-c`、脚本），并**删除**了危险的
  `toShellPrefix()` 以防再次误用。
- **结束一个实例会杀掉全部实例**：两处路径在发出带 id 的停止意图后又发了一次
  不带 id 的（语义为「停全部」）。已移除。
- **分块下载可能拼出损坏归档**：分块复用原先只校验长度，而镜像顺序按延迟排序、
  并不稳定，同长度的异地分块可能被拼进来；且 Debian/Ubuntu 的清单 `sha256` 为空，
  下游无法发现。现在复用需同时匹配**长度 + `.src` 来源标记**。
- **镜像探测连接泄漏**：回退分支的 `Range` 响应未关闭 body。
- **实例配置可能不同步**：`InstanceStore` 原先是无锁缓存且各调用点各自 `new`，
  保存的限制可能传不到服务。改为进程内单例 + `synchronized`。
- **换源代号兜底是死代码**：表达式恒等，导致静默退化为 `"stable"`，
  Ubuntu 会因此 `apt update` 404。已改为真实兜底链。

### 变更

- **`docs/` 目录纳入版本控制**。此前为 untracked，`git clone` 拿不到文档。
- CI 工作流重写：新增 `beta` 分支自动构建、tag 自动发行、手动触发（可选发行）；
  编译前增加 §8 静态约束检查；APK 改名 `ProotTerm-v<版本>-<release|debug>-<短SHA>.apk`。
- `AGENTS.md` §8 硬性约束由 16 条扩充至 **21 条**（新增 §8.17~§8.21）。

---

## [v0.0.0.3] - 2026-09-24

### 修复

- 修复 `MainActivity` 标题类型不匹配。
- 修复 `item_distro.xml` 的 AAPT 资源属性命名错误。
- `extractNativeLibs` 改由 Gradle DSL（`useLegacyPackaging`）管理，
  源码 Manifest 不再写字面量。

---

## [v0.0.0.2] - 2026-09-24

### 新增

- 多版本 rootfs 重构：三发行版家族 × 多历史版本。
- `AppLogger` + `LogActivity`：内存环形缓冲 + `filesDir/logs/app.log`（1MB 滚动），
  支持查看 / 清空 / 复制 / 一键分享（FileProvider `content://`）。
- Termux 风格终端快捷键（ESC / CTRL / ALT / TAB / 方向键 / HOME / END / PGUP / PGDN）。
- `MainActivity` 四屏重写。

---

## [v0.0.0.1] - 2026-09-24

### 新增

- 首个可用版本：PRoot 免 root Linux 终端。
- 三发行版（Alpine / Debian / Ubuntu）下载、SHA256 校验、解压、启动。
- Material 浅色界面；签名 V2/V3。

---

## 版本号约定

| 段 | 何时提升 | 例子 |
| --- | --- | --- |
| `MAJOR` | 不兼容的存储布局 / 数据格式变更 | `0.x` → `1.0.0` |
| `MINOR` | 新增功能 | `0.0.3` → `0.1.0` |
| `PATCH` | 仅修 bug | `0.0.1` → `0.0.2` |

**同时必须提升 `versionCode`**（单调递增的整数），否则 Android 会拒绝覆盖安装。
两者都在 `app/build.gradle.kts` 中手工维护。

---

## 相关文档

| 文档 | 内容 |
| --- | --- |
| [docs/RELEASE.md](docs/RELEASE.md) | 发版操作手册：提版本、打 tag、写 Release Notes、回滚 |
| [docs/CI.md](docs/CI.md) | 持续集成详解：触发条件、Secrets、排查失败 |
| [AGENTS.md](AGENTS.md) §8 | 21 条硬性约束（改代码前必读） |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | 深度架构与设计决策 |
| [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md) | 按症状排查 |
