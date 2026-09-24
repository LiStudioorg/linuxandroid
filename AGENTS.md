# AGENTS.md — LinuxAndroid 技术方案

本文件面向 AI 编码代理与开发者，规定项目结构、实现方案与编码约定。修改代码前必须通读。

## 1. 项目概述

LinuxAndroid（界面品牌名 **ProotTerm**）是一个免 root 的 Android 应用：内置 PRoot（Termux 适配版）二进制，在应用私有目录解压用户选择的 Linux rootfs（Alpine / Debian / Ubuntu），并通过一个简易文本终端驱动其中的 shell。

- applicationId / namespace / Kotlin 包名：**com.li63050a.linuxandroid**（三者一致，Manifest 的 `.MainActivity` 依赖 namespace 解析，源码包名不得再改回其他值）
- versionCode 1 / versionName `0.0.0.1`
- 目标架构：仅 `arm64-v8a`
- 语言：Kotlin 100%，界面：XML 布局 + `android.app.Activity`（不引入 AppCompat / Material 组件）
- rootfs 解压后的目录：`filesDir/rootfs/<distroId>`
- 不申请任何存储权限，全部使用应用私有目录
- Git 远程：`git@github.com:LiStudioorg/linuxandroid.git`（SSH，禁止改回 HTTPS）

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

`android.system.Os.*`（symlink/link/chmod/lstat/remove）自 API 21 可用，可直接使用。

### 签名配置（app/build.gradle.kts）

优先级：**环境变量（GitHub Actions 注入）> local.properties（本地）> 兜底 `myapp.jks`**。

- 环境变量 / local.properties 键名一致：`KEYSTORE_PATH`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`
- local.properties 已 gitignore；CI 见第 7 节 Secrets

## 3. 目录结构

```
AGENTS.md / README.md
settings.gradle.kts               # rootProject.name = "LinuxAndroid"
build.gradle.kts                  # AGP 8.11.1 + Kotlin 2.0.21
gradle.properties
gradlew / gradlew.bat             # Gradle 8.13 wrapper（入库，CI 直接 ./gradlew）
gradle/wrapper/{gradle-wrapper.jar, gradle-wrapper.properties}
.gitignore                        # 排除 jniLibs/**/*.so（CI 自动补拉）
.github/workflows/android.yml     # tag → Release；workflow_dispatch → 仅产物
app/
  build.gradle.kts                # 包名/SDK/签名/packaging.jniLibs
  proguard-rules.pro
  src/main/
    AndroidManifest.xml           # extractNativeLibs="true"，仅 INTERNET 权限
    assets/
      rootfs_manifest.json        # 三发行版清单
      native/arm64-v8a/           # 运行期依赖（入库）
        libtalloc.so.2            # proot NEEDED；名字不以 .so 结尾不进 jniLibs
        libandroid-shmem.so       # proot NEEDED
    jniLibs/arm64-v8a/            # *.so 不入库，CI/本地手动提取
      libproot.so                 # ← usr/bin/proot
      libproot-loader.so          # ← usr/libexec/proot/loader
      README.txt                  # 提取步骤说明
    res/layout/activity_main.xml
    res/values/{strings,themes,colors}.xml
    java/com/li63050a/linuxandroid/
      DistroInfo.kt               # 数据类
      ManifestLoader.kt           # assets JSON 解析
      RootfsManager.kt            # 目录规划、安装标记、原子切换
      RootfsDownloader.kt         # OkHttp 下载（断点续传 + 多镜像）
      RootfsExtractor.kt          # tar 解压（符号链接/权限/防穿越，API24 安全）
      ProotSession.kt             # 持久 shell 进程生命周期 + LD_LIBRARY_PATH
      NativeDeps.kt               # 释放 assets 运行库到 filesDir/native
      Sha256Utils.kt              # 流式 SHA256
      MainActivity.kt             # UI 编排
```

## 4. 关键流程

### 4.1 安装（点击“下载”按钮）

```
下载 .part（断点续传）
  → SHA256 校验（清单 sha256 非空时）
  → 解压到 filesDir/tmp_<id>（保留符号链接与权限位，防路径穿越）
  → 删除旧 filesDir/rootfs/<id>，tmp 目录原子 rename 为最终目录
  → 在最终目录写入 .installed 标记，删除 .part 缓存
  → 按钮变为“启动”
```

失败：删半成品 tmp；`.part` 保留供续传；按钮恢复“下载”。

### 4.2 下载（RootfsDownloader）

- 候选地址：`url` → `mirrors[]`，失败自动切换，`.part` 保留
- 临时文件：`filesDir/<id>.<format>.part`
- 已有字节 > 0 时发 `Range: bytes=<n>-` + `Accept-Encoding: identity`
- `206`：校验 Content-Range 起点后追加；`200`：截断覆盖；`416`：视为已完整
- 实际字节 < 服务端总长 → 抛“下载不完整”进入下一镜像
- 进度回调节流 200ms；总长未知时 UI 兜底用清单 `size`（仅展示，不参与完整性判断）
- 取消：`cancel()` 结束活动 Call → `ensureActive()` 抛 CancellationException，不再切镜像

### 4.3 解压（RootfsExtractor，API 24 安全）

- `format` → `GzipCompressorInputStream` / `XZCompressorInputStream` 包 `TarArchiveInputStream`
- 路径穿越：条目名去前导 `/` 拼接后，`canonicalPath` 必须等于 dest 或以 `dest + separator` 开头
- 普通文件：`Os.chmod(path, entry.mode and 0x1FF)`（mode 0 跳过）
- 符号链接：`Os.symlink(entry.linkName, outFile.absolutePath)`，linkName 原文写入
- 删除已存在路径：`Os.lstat` + `Os.remove`（不跟随链接；**禁用 java.nio.file**）
- 硬链接：主循环收集，结束后多轮重试直至无进展
- 目录：解压期间保持默认可写，全部结束后逆序统一 `chmod` 还原

### 4.4 PRoot 启动（ProotSession）

```
<nativeLibraryDir>/libproot.so
  -r <rootfs> -0 -w /root
  -b /dev -b /proc -b /sys -b /dev/urandom:/dev/random
  <defaultShell>
```

必设环境变量（全部绝对路径）：

| 变量 | 值 |
| --- | --- |
| `PROOT_TMP_DIR` | `filesDir/proot_tmp` |
| `PROOT_LOADER` | `nativeLibraryDir/libproot-loader.so` |
| `LD_LIBRARY_PATH` | `filesDir/native`（NativeDeps 释放的 libtalloc/libandroid-shmem；proot 内置 RUNPATH 指向 Termux 私有目录，其他设备不可用） |

guest 侧环境：`TERM=xterm-256color`、`HOME=/root`、`LANG=C.UTF-8`、`TMPDIR=/tmp`、guest `PATH`。

- `redirectErrorStream(true)`；后台线程 `InputStreamReader(UTF_8)` 读取，主线程 Handler 回调
- 启动前 `NativeDeps.ensure()` 释放运行库；`prootBin.setExecutable(true)` 兜底
- 非 PTY 无提示符/无回显：UI 本地回显 `$ <cmd>`；README 提示 `sh -i`
- API 24：`isRunning` 用 `exitValue()`；destroy 后台线程低版本仅 `waitFor()`

### 4.5 UI（MainActivity）

- 三个 Button 与清单按索引绑定（`btnDistro0/1/2`）：`下载 <name>` / `下载中 n% <name>` / `启动 <name>`
- 同时仅一个安装任务；安装期间禁用全部按钮
- 终端区：ScrollView + 等宽 TextView（`textIsSelectable`），超 200KB 截头防 OOM
- 作用域 `MainScope()`；`onDestroy` 取消作用域、cancel 下载 Call、destroy shell
- 根布局 `fitsSystemWindows="true"`（targetSdk 35+ 边到边）

## 5. 数据与文件布局

```
filesDir/
  rootfs/<id>/            # 最终 rootfs（含 .installed）
  tmp_<id>/               # 解压临时目录
  <id>.<format>.part      # 断点续传
  native/                 # NativeDeps 释放的 libtalloc.so.2 等
  proot_tmp/              # PROOT_TMP_DIR
```

## 6. 清单格式（assets/rootfs_manifest.json）

顶层 `{"version": 1, "distros": [...]}`，字段均必填，`mirrors`/`sha256` 可为空：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | string | 唯一标识，目录名，如 `alpine-3.20` |
| name | string | 按钮显示名 |
| description | string | 描述，状态栏展示 |
| size | long | 预计字节（进度 UI 兜底，不参与完整性校验） |
| url | string | 主下载地址 |
| mirrors | string[] | 备用镜像 |
| sha256 | string | 小写十六进制；空串跳过校验 |
| format | string | `tar.gz` / `tar.xz` |
| defaultShell | string | guest shell 绝对路径 |

已知校验值：Alpine `041fa34a…f3de`、Ubuntu 24.04.3 arm64 `7b2dced6…b048`；Debian 留空。

## 7. 构建与验证

### 本地

```bash
# 1) 放入 PRoot（二选一）
#    a) 手动：见 app/src/main/jniLibs/arm64-v8a/README.txt（deb 提取步骤）
#    b) 直接跑 workflow 里的 Fetch PRoot binaries 命令段

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
3. 步骤顺序：Checkout → **Fetch PRoot binaries from Termux deb** → JDK17 → Android SDK → Decode Keystore → chmod gradlew → `./gradlew assembleRelease` → Upload → Release
4. PRoot 拉取逻辑（保证 jniLibs 两个 .so 就位，否则真机启动即崩）：
   - 下载 `https://packages.termux.dev/apt/termux-main/pool/main/p/proot/proot_5.1.107.94_aarch64.deb`
   - `dpkg-deb -x` 解开，拷贝
     `usr/bin/proot` → `libproot.so`、
     `usr/libexec/proot/loader` → `libproot-loader.so`
   - 目标目录 `app/src/main/jniLibs/arm64-v8a/`；文件已存在则跳过
5. 所需 Secrets：`KEYSTORE_BASE64`（jks 的 base64）、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`

### 终检清单

```bash
grep -r "com.example" app/src/main/java/   # 必须为空
ls -l gradle/wrapper/                       # jar + properties 齐全
git remote -v                               # 必须是 git@github.com:LiStudioorg/linuxandroid.git
test -f app/src/main/jniLibs/arm64-v8a/libproot.so && echo local-so-ok
./gradlew assembleDebug
```

## 8. 硬性约束（禁止违反）

1. 不引入 AppCompat/Material/RecyclerView；布局仅 framework 控件。
2. 不申请存储权限；不读写应用私有目录以外路径。
3. Manifest 必须 `android:extractNativeLibs="true"`；Gradle 必须 `packaging { jniLibs { useLegacyPackaging = true } }`。
4. 仅 `arm64-v8a`。
5. 包名三处一致：`com.li63050a.linuxandroid`（namespace / applicationId / Kotlin package）。
6. 禁止使用 `java.nio.file`；`Process.isAlive/waitFor(timeout)/destroyForcibly` 必须 SDK_INT 分支。
7. 下载必须 `.part` + Range；解压必须先 tmp 再 rename；符号链接、权限位、路径穿越三者缺一不可。
8. `PROOT_TMP_DIR`、`PROOT_LOADER`、`LD_LIBRARY_PATH` 必须设置为绝对路径。
9. `jniLibs/**/*.so` 不入库（workflow 自动拉取）；`assets/native/**` 运行库必须入库。
10. 新增发行版只改 `rootfs_manifest.json`（及必要时按钮数量），不动下载/解压核心逻辑。
11. 远程仓库只用 SSH：`git@github.com:LiStudioorg/linuxandroid.git`。
