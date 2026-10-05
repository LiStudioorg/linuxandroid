# 开发者指南（CONTRIBUTING）

> 面向**第一次参与本项目**的开发者。目标：让你在 30 分钟内把项目跑起来、改对地方、验证有效、安全提交。
>
> 配套文档：
> - [AGENTS.md](../AGENTS.md) —— 编码约定与 12 条硬性约束（**改代码前必读**）
> - [ARCHITECTURE.md](ARCHITECTURE.md) —— 分层、四条核心链路、设计决策
> - [MODULES.md](MODULES.md) —— 逐类 API 契约与坑
> - [TESTING.md](TESTING.md) —— 测试策略与手测矩阵
> - [TROUBLESHOOTING.md](TROUBLESHOOTING.md) —— 故障排查手册
> - [COMPLIANCE.md](COMPLIANCE.md) —— 约束合规审查报告

---

## 1. 这个项目是什么

免 root 的 Android 应用。内置 PRoot（Termux 适配版）二进制，在**应用私有目录**里解压用户选择的 Linux rootfs（Alpine / Debian / Ubuntu），用一个纯文本终端驱动其中的 shell。

关键前提：**不需要 root，不申请任何存储权限**，全部读写都在 `filesDir` 内。

| 项 | 值 |
| --- | --- |
| 包名 | `com.li63050a.linuxandroid` |
| 界面品牌名 | ProotTerm |
| 语言 | Kotlin 100%（无 Java、无 Compose） |
| 界面 | XML 布局 + AppCompat + Material Components |
| ABI | 仅 `arm64-v8a` |
| minSdk / targetSdk | 24 / 36 |

---

## 2. 环境准备

### 2.1 必需

| 工具 | 版本 | 说明 |
| --- | --- | --- |
| JDK | **17** | AGP 8.11.1 要求；用 21 可能报 `Unsupported class file major version` |
| Android SDK | Platform **36** | `compileSdk = 36`；同时装 Build-Tools |
| Git | 任意 | |
| 一台 **arm64 Android 设备** | API 24+ | **必须有真机或 arm64 模拟器**，见下方警告 |

Gradle 无需单独安装：仓库已含 wrapper（8.13），首次构建自动下载。

### 2.2 配置 SDK 路径

```bash
# 方式一：环境变量（推荐）
export ANDROID_HOME="$HOME/Android/Sdk"
export PATH="$ANDROID_HOME/platform-tools:$PATH"

# 方式二：local.properties（不污染环境变量，已被 gitignore）
echo "sdk.dir=$HOME/Android/Sdk" >> local.properties
```

安装所需的 SDK 组件：

```bash
sdkmanager "platforms;android-36" "build-tools;36.0.0" "platform-tools"
yes | sdkmanager --licenses
```

### 2.3 ⚠️ 关于模拟器：绝大多数 x86 模拟器跑不起来

应用 `abiFilters` 只保留 `arm64-v8a`，且 `libproot.so` 是 **aarch64 ELF 可执行文件**。这意味着：

- ❌ **x86_64 模拟器**：APK 能装，但 `libproot.so` 无法执行，PRoot 必然启动失败。
- ❌ **Apple Silicon Mac 上的 x86 Android 模拟器**：同上。用 **arm64 系统镜像**（`system-images;android-34;google_apis;arm64-v8a`）可以。
- ✅ **真机（arm64）**：推荐，本项目主要验证路径。

> 如果你只改 UI / 清单解析，x86 模拟器够用；只要涉及 PRoot 启动或解压，**必须 arm64**。

### 2.4 首次构建

```bash
git clone https://github.com/LiStudioorg/linuxandroid.git
cd linuxandroid
./gradlew assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

调试包**不需要签名配置**（用默认 debug key）。只有 `assembleRelease` 才需要签名四件套。

### 2.5 本地签名（仅 release 需要）

优先级：**环境变量 > `local.properties` > 兜底 `myapp.jks`**。

```bash
cat >> local.properties <<'EOF'
KEYSTORE_PATH=/abs/path/to/my.jks
KEYSTORE_PASSWORD=你的密码
KEY_ALIAS=你的别名
KEY_PASSWORD=你的密码
EOF

./gradlew assembleRelease
```

`local.properties` 与 `*.jks` 均已 gitignore，不会误提交。

---

## 3. 代码地图：改哪里？

```
app/src/main/
  assets/rootfs_manifest.json      ← 加发行版/版本：只改这里
  assets/native/arm64-v8a/*.so     ← 运行期依赖（已入库，勿删）
  jniLibs/arm64-v8a/*.so           ← PRoot 二进制（已入库，勿删）
  res/layout/*.xml                 ← 界面
  res/values/{strings,themes,colors}.xml
  java/com/li63050a/linuxandroid/   ← 20 个 Kotlin 文件，约 4100 行
    App.kt               全局 Application：日志 + SessionManager 初始化 + 崩溃兜底
    AppLogger.kt         日志（内存缓冲 + 文件 + Logcat）
    LogActivity.kt       日志页
    DistroInfo.kt        数据模型（DistroFamily / DistroInfo）
    MyApp.kt             MyApp 数据类 + InstalledScanner（扫描已装系统）
    StorageLocation.kt   存储位置枚举（内部 / 外部私有沙箱）
    AppPrefs.kt          SharedPreferences：存储位置 / 自启动 / 最近系统
    ManifestLoader.kt    清单解析 + 结构校验
    RootfsManager.kt     目录规划（按存储位置）、空间检查、可回滚原子安装
    RootfsDownloader.kt  OkHttp 断点续传 + 多镜像（单线程路径）
    MultiPartDownloader.kt  多线程分块并发下载 + 合并校验 + 来源标记
    MirrorProbe.kt       镜像并发测速与优选（超时兜底）
    AptSourceSwitcher.kt APT/APK 换源：deb822 / legacy / Alpine 三格式，可逆
    RootfsExtractor.kt   tar 解压（符号链接/权限/防穿越）
    ResourceLimits.kt    ulimit 语义的资源限制 → proot argv（§8.18）
    InstanceConfig.kt    每实例配置 + InstanceStore（instances.json 持久化）
    ProotSession.kt      单个 shell 进程 + 输出缓冲（不持有 Activity）
    SessionManager.kt    多会话注册表：每实例独立槽 / 状态机 / 代次  ← 会话的"大脑"
    ProotService.kt      前台服务：保活 + 常驻通知（会话唯一权威）
    BootReceiver.kt      开机自启动入口
    NativeDeps.kt        释放 assets 运行库
    Sha256Utils.kt       流式校验
    DistroAdapter.kt     家族卡片列表
    VersionAdapter.kt    版本列表 + 状态机
    MyAppAdapter.kt      「我的系统」列表（含运行中徽标）
    MainActivity.kt      五屏编排 + 业务流程调度（只观察会话，不拥有会话）
```

| 我想…… | 去改 |
| --- | --- |
| 新增发行版 / 历史版本 | `assets/rootfs_manifest.json` **（只改清单，不动 Kotlin）** |
| 改下载 / 断点续传 | `RootfsDownloader.kt`（单线程） |
| **改多线程分块下载 / 合并** | `MultiPartDownloader.kt`（⚠️ 分块复用需"长度 + `.src` 来源标记"双匹配，§8.19） |
| **改镜像测速 / 优选** | `MirrorProbe.kt`（⚠️ 全失败必须回退清单顺序，不得阻断下载，§8.20） |
| **改资源限制** | `ResourceLimits.kt`（⚠️ 必须传 3 个独立 argv，§8.18）+ `dialog_instance_settings.xml` |
| **改实例级配置持久化** | `InstanceConfig.kt`（⚠️ 必须用 `InstanceStore.of()` 共享实例，§8.17） |
| **改 APT / APK 换源** | `AptSourceSwitcher.kt`（⚠️ 备份不得覆盖、必须可恢复，§8.21） |
| 改解压行为 | `RootfsExtractor.kt` |
| 改安装落盘 / 卸载 | `RootfsManager.kt` |
| 改 PRoot 参数 / 环境变量 | `ProotSession.kt` |
| 改终端交互 / 快捷键 | `MainActivity.kt` + `res/layout/view_terminal.xml` |
| 改版本卡片按钮 / 状态 | `VersionAdapter.kt` + `res/layout/item_version.xml` |
| 改文案 | `res/values/strings.xml`（**不要硬编码到 Kotlin**） |
| **改会话状态机 / 启停逻辑 / 多实例隔离** | `SessionManager.kt` ← 先读 `ARCHITECTURE.md` §13 与 §8.17 |
| **改后台保活 / 通知** | `ProotService.kt`（⚠️ `onDestroy` **禁止**杀会话，§8.13） |
| **改开机自启动** | `BootReceiver.kt`（只踢一脚，校验都在服务里） |
| **改存储位置 / 目录规划** | `StorageLocation.kt` + `AppPrefs.kt` + `RootfsManager.kt` ← 见 `ARCHITECTURE.md` §17 |
| **改「我的系统」列表** | `MyApp.kt`（扫描逻辑）+ `MyAppAdapter.kt` + `view_myapps.xml` |

---

## 4. 开发工作流

### 4.1 改代码前：读 AGENTS.md §8

那 **16 条**是**硬性约束**，违反会导致行为错误或安全问题。最容易踩的六条：

1. **禁止 `java.nio.file`**（`Files` / `toPath()`）—— minSdk 24 不可用。删文件用 `Os.lstat` + `Os.remove`。
2. **`Process.isAlive` / `waitFor(timeout)` / `destroyForcibly` 必须做 `SDK_INT >= O` 分支** —— 这三个是 API 26+。
3. **下载必须 `.part` + `Range`；解压必须先 tmp 再 rename** —— 不能简化成直接写目标目录。
4. **不要引入 Compose** —— 界面保持 XML + AppCompat/Material。
5. **`onDestroy` 里绝不能杀会话**（§8.13）—— `MainActivity` 和 `ProotService` **都**不行。
   `onDestroy` 不等于「用户要结束会话」：系统在内存紧张、OEM 后台清理时都会销毁服务，
   而那正是前台服务要扛住的场景。唯一权威结束点是通知栏的「结束」按钮。
6. **命令输入框必须用 `@style/App.TerminalInput`**（§8.15）—— 改成 `textPassword`
   会触发系统安全键盘；用 `actionSend` 这类 `imeOptions` 会抢走回车键。

完整清单见 [AGENTS.md §8](../AGENTS.md)。会话相关的三条约束（§8.13/8.14/8.15）
背后都有真实的 bug 历史，改动前请先读 [ARCHITECTURE.md §13](ARCHITECTURE.md)。

### 4.2 改代码时

```bash
# 快速编译（不跑测试，本项目暂无自动化测试）
./gradlew assembleDebug

# 只看 Kotlin 编译错误
./gradlew compileDebugKotlin

# 资源链接（AAPT）
./gradlew processDebugResources
```

### 4.3 安装到设备并看日志

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.li63050a.linuxandroid/.MainActivity

# 实时日志（本项目所有日志都带统一 tag）
adb logcat -s ProotTerm:V AndroidRuntime:E
```

`AppLogger` 会同时写 Logcat、内存缓冲和 `filesDir/logs/app.log`。应用内**抽屉 → 日志**可直接查看、复制、分享。

导出日志文件：

```bash
adb shell run-as com.li63050a.linuxandroid cat files/logs/app.log
```

---

## 5. 提交前自检

至少跑完这几条（也是 [AGENTS.md §7](../AGENTS.md) 的终检清单）：

```bash
# 1) 包名不得出现 com.example
grep -r "com.example" app/src/main/java/          # 必须为空

# 2) 禁用 API 不得出现
grep -rn "java.nio.file\|toPath()" app/src/main/java/   # 必须为空

# 3) wrapper 完整
ls -l gradle/wrapper/                              # jar + properties 都在

# 4) PRoot 二进制在
test -f app/src/main/jniLibs/arm64-v8a/libproot.so && echo local-so-ok

# 5) 能编译
./gradlew assembleDebug

# 6) 确认 4 个 .so 进了 APK
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep -E '\.so'
# 期望看到：lib/arm64-v8a/libproot.so、lib/arm64-v8a/libproot-loader.so
#          assets/native/arm64-v8a/libtalloc.so.2、libandroid-shmem.so
```

### 提交信息

沿用现有的 `type: 描述` 风格（Conventional Commits）：

```
feat: 支持 tar.zst 格式解压
fix: 修复 CTRL 字母键无对应控件导致组合键不可达
docs: 补充开发者指南
refactor: 拆分 MainActivity 安装编排逻辑
```

已在用的类型：`feat` / `fix` / `docs` / `refactor` / `chore`。

### PR 检查清单

- [ ] `./gradlew assembleDebug` 通过
- [ ] 未违反 [AGENTS.md §8](../AGENTS.md) 的 **16 条**硬性约束
- [ ] 新增文本走 `strings.xml`，未硬编码中文到 Kotlin
- [ ] 若改了清单结构，已同步提升 `ManifestLoader.SUPPORTED_VERSION`
- [ ] 若新增发行版，**只改了 `rootfs_manifest.json`**，未动下载/解压核心逻辑
- [ ] 若改了 `VersionAdapter.State` 或 `ProotSession.ExitReason`，已处理全部 `when` 分支（穷尽性）
- [ ] **若改了 `SessionManager` / `ProotService` / `BootReceiver`**：
  - [ ] 两个 `onDestroy` 都**没有**调用 `SessionManager.stop(id)`
  - [ ] `startBlocking` 的返回值仍区分 `null`（成功）/ `ABORTED`（取消）/ 其他（失败）
  - [ ] 新增的启动失败路径都调用了 `notifyStartFailed`（否则 UI 卡在「正在启动…」）
  - [ ] 已在 [ARCHITECTURE.md §13](ARCHITECTURE.md) 记录设计变更
- [ ] **若改了 `StorageLocation` / `AppPrefs` / `RootfsManager`**：
  - [ ] `prootTmpDir()` / `logsDir()` / `nativeDir()` 仍**不读** `location`（§8.14）
  - [ ] `tempDir` 与 `distroDir` 仍同源（保证 `renameTo` 不跨文件系统）
- [ ] **若改了终端输入框**：仍使用 `@style/App.TerminalInput`，无 `textPassword` / 动作型 `imeOptions`
- [ ] **若改了多实例相关逻辑**：
  - [ ] 没有新增无参的「当前会话」访问器（§8.17）
  - [ ] 新增的 `SessionManager.Observer` 回调都按 `id` 过滤（否则输出会串实例）
  - [ ] 结束某个实例时用的是带 id 的 `stopIntent(this, id)`，**不是**不带 id 的「停全部」
- [ ] **若改了资源限制**：
  - [ ] 仍是 **3 个独立 argv** `/bin/sh` `-c` 脚本（§8.18，拼成单字符串会让所有受限会话起不来）
  - [ ] 脚本末尾仍有 `exec`（否则 `destroy()` 留下孤儿 shell）
  - [ ] `0` 仍表示不限制且不生成对应语句；UI 文案未把 `-t` 描述成「CPU 核数」
- [ ] **若改了下载器**：
  - [ ] 分块仍要求 HTTP 206，返回 200 时降级单线程而非直接失败（§8.19）
  - [ ] 分块复用仍同时校验长度与 `.src` 来源标记
  - [ ] 取消/失败/卸载三条路径都清理了分块与标记
- [ ] **若改了镜像逻辑**：全部探测失败仍回退清单顺序，测速失败不阻断下载（§8.20）
- [ ] **若改了换源**：备份仍不被覆盖、仍可恢复、写文件仍是原子的（§8.21）
- [ ] 已在 arm64 设备上验证（涉及 PRoot / 解压 / 会话 / 存储位置时）
- [ ] 相关文档已同步更新（ARCHITECTURE / MODULES / 本文件）

---

## 6. 提交文档

### 文档职责划分（避免重复）

| 文档 | 回答什么问题 | 读者 |
| --- | --- | --- |
| [README.md](../README.md) | 这是什么、怎么装、怎么用 | 用户 |
| [AGENTS.md](../AGENTS.md) | 必须遵守什么规则 | AI / 开发者 |
| [ARCHITECTURE.md](ARCHITECTURE.md) | 系统怎么运作、为什么这么设计 | 开发者 |
| [MODULES.md](MODULES.md) | 这个类怎么用、有什么坑 | 改代码的人 |
| [CONTRIBUTING.md](CONTRIBUTING.md) | 我怎么开始干活 | 新贡献者 |
| [TESTING.md](TESTING.md) | 怎么验证改动是对的 | 开发者 / 测试 |
| [TROUBLESHOOTING.md](TROUBLESHOOTING.md) | 出问题怎么查 | 用户 / 开发者 |
| [COMPLIANCE.md](COMPLIANCE.md) | 当前代码是否合规 | 维护者 |

**原则**：同一事实只在一处详述，其他地方链接过去。改接口时优先更新 MODULES.md，改设计时更新 ARCHITECTURE.md。

### ✅ `docs/` 已纳入版本控制

`docs/` 已随「多实例隔离 + 资源限制 + 镜像增强 + APT 换源」提交入库，`git clone` 即可拿到全部文档。

改代码时请把文档改动**放进同一个提交**（§9.5），不要单独留在工作区：

```bash
git add app/ docs/ AGENTS.md && git commit -m "..."
```

---

## 7. 常见起步问题

| 现象 | 原因与解决 |
| --- | --- |
| `Unsupported class file major version` | JDK 版本不对，需要 **17**。`java -version` 确认 |
| `SDK location not found` | 未设 `ANDROID_HOME` 或 `local.properties` 的 `sdk.dir` |
| `Failed to install ... platform 36` | 跑 `sdkmanager "platforms;android-36"` |
| AAPT 报资源属性错误 | 检查 XML 属性名是否真实存在（历史上 `item_distro.xml` 踩过） |
| 应用装上了但点「启动」无反应 | 大概率在 x86 模拟器上，`libproot.so` 是 aarch64，换真机 |
| 下载总失败 | 见 [TROUBLESHOOTING.md](TROUBLESHOOTING.md) 的下载章节 |
| 构建很慢 | 首次要下载 Gradle 8.13 与依赖；后续增量编译很快 |

更多运行时故障见 [TROUBLESHOOTING.md](TROUBLESHOOTING.md)。

---

## 8. 当前项目状态（新贡献者须知）

如实告知，避免踩空：

- **无自动化测试**：没有 `app/src/test` 或 `app/src/androidTest`，也没有测试依赖。验证靠手工（见 [TESTING.md](TESTING.md)）。
- **无版本目录**：依赖版本直接写在 `app/build.gradle.kts`，未用 `gradle/libs.versions.toml`。
- **`isMinifyEnabled = false`**：release 也未开混淆，`proguard-rules.pro` 仅占位。
- **`MainActivity` 约 1700 行**：职责偏重，拆分是已知待办（见 [ARCHITECTURE.md §19](ARCHITECTURE.md) 第 4 条）。
- **构建未在 CI 之外验证**：仓库有 GitHub Actions（tag 触发 Release），但本地开发环境需自备 JDK/SDK。
- **会话架构分三层**（`ProotSession` / `SessionManager` / `ProotService`）：这是本项目最需要先理解的部分，
  改任何与会话、后台、自启动、存储位置、资源限制、镜像、换源相关的代码前，请先读 [ARCHITECTURE.md §13–18](ARCHITECTURE.md)。
- **外部存储位置在 Android 11+ 上可能跑不起来**：`/Android/data` 由 FUSE 提供、通常不可执行，
  proot 可能启动失败。这是平台限制，不是待修的 bug（见 [ARCHITECTURE.md §19](ARCHITECTURE.md) 第 17 条）。

这些都是**已知且记录在案**的状态，不是你需要偷偷绕过的坑。
