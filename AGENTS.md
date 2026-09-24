# AGENTS.md — LinuxAndroid 技术方案

本文件面向 AI 编码代理与开发者，规定项目结构、实现方案与编码约定。修改代码前必须通读。

## 1. 项目概述

LinuxAndroid（界面品牌名 **ProotTerm**）是一个免 root 的 Android 应用：内置 PRoot（Termux 适配版）二进制，在应用私有目录解压用户选择的 Linux rootfs（Alpine / Debian / Ubuntu），并通过一个简易文本终端驱动其中的 shell。

- applicationId / namespace / Kotlin 包名：**com.li63050a.linuxandroid**（三者一致，Manifest 的 `.MainActivity` 依赖 namespace 解析，源码包名不得再改回其他值）
- versionCode 1 / versionName `0.0.0.1`
- 目标架构：仅 `arm64-v8a`
- 语言：Kotlin 100%，界面：XML 布局 + **AppCompat + Material Components**（DrawerLayout / NavigationView / MaterialToolbar / MaterialCardView / RecyclerView，不引入 Compose）
- rootfs 解压后的目录：`filesDir/rootfs/<versionId>`（如 `alpine-3.20`）
- 日志：`AppLogger` → 内存环形缓冲 + `filesDir/logs/app.log`（1MB 滚动 `.old`）+ Logcat；`LogActivity` 查看/复制/一键分享（FileProvider `content://`）
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
.gitignore                        # 不排除 jniLibs *.so（已入库）
.github/workflows/android.yml     # tag → Release；workflow_dispatch → 仅产物（无 Fetch 步骤）
app/
  build.gradle.kts                # 包名/SDK/签名/packaging.jniLibs
  proguard-rules.pro
  src/main/
    AndroidManifest.xml           # extractNativeLibs="true"，.App + MainActivity + LogActivity + FileProvider
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
    res/layout/{activity_main,activity_log,nav_header,view_terminal,view_distros,
                view_versions,view_settings,item_distro,item_version}.xml
    res/menu/nav_menu.xml         # 终端/发行版/日志/设置（默认发行版）
    res/drawable/{ic_launcher,ic_menu,ic_search,ic_terminal,ic_distro,ic_settings,
                  ic_logs,ic_back,bg_search,bg_terminal,bg_circle}.xml
    res/values/{strings,themes,colors}.xml
    java/com/li63050a/linuxandroid/
      App.kt                      # 全局 Application：AppLogger 初始化 + 未捕获异常落盘
      AppLogger.kt                # 日志：内存缓冲 + logs/app.log + Logcat
      LogActivity.kt              # 日志页：刷新/清空/复制/分享（FileProvider content://）
      DistroInfo.kt               # DistroFamily / DistroVersion / DistroInfo 数据类
      ManifestLoader.kt           # assets JSON 解析（version 2 嵌套结构）
      RootfsManager.kt            # 目录规划、安装标记、原子切换
      RootfsDownloader.kt         # OkHttp 下载（断点续传 + 多镜像）
      RootfsExtractor.kt          # tar 解压（符号链接/权限/防穿越，API24 安全）
      ProotSession.kt             # 持久 shell 进程生命周期 + LD_LIBRARY_PATH
      NativeDeps.kt               # 释放 assets 运行库到 filesDir/native
      Sha256Utils.kt              # 流式 SHA256
      DistroAdapter.kt            # 家族卡片 RecyclerView 适配器
      VersionAdapter.kt           # 版本列表 RecyclerView 适配器
      MainActivity.kt             # Drawer 四屏编排（终端/发行版/版本/设置）
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

### 4.5 UI（MainActivity，四屏 Screen 枚举）

- 结构：`DrawerLayout + NavigationView`，**默认「发行版管理」**；顶栏 `MaterialToolbar` 汉堡开抽屉，副标题显示全局状态
  1. **发行版管理**（Screen `DISTROS`）`view_distros.xml` + `DistroAdapter`：三家族卡片（Alpine/Debian/Ubuntu），点击进入版本页
  2. **版本选择**（Screen `VERSIONS`）`view_versions.xml` + `VersionAdapter`：该家族多历史版本（大小/描述/下载-启动-卸载）；返回键回发行版页
  3. **终端页**（Screen `TERMINAL`）`view_terminal.xml`：搜索框过滤输出 + 纯黑等宽终端 + 快捷键行（ESC/CTRL/TAB/方向键，CTRL 组合经 `btn_key_<ch>` 查找）+ 命令输入/发送
  4. **设置页**（Screen `SETTINGS`）`view_settings.xml`：版本/包名/ABI/SDK/rootfs 路径、GitHub 链接、清空终端
  - **nav_logs** 不占 Screen，直接 `startActivity(LogActivity::class.java)`
- 终端**返回键**：会话运行中弹「保持运行 / 关闭终端」对话框；否则回发行版页
- 图标：全部 vector drawable；应用图标 `ic_launcher.xml`（`>_` 深色圆角方块）
- 状态色：页面 `#F5F5F5`、强调粉 `#FFB6C1` / 浅蓝 `#ADD8E6`；状态栏 `windowLightStatusBar`
- 同时仅一个安装任务；家族/版本卡片状态由 `DistroAdapter.setState` / `VersionAdapter.setState` 驱动；下载中切屏用 `lastDownloadPct` 恢复进度
- 安装/启动全包在 `Dispatchers.IO` + 全局 try-catch + 下载前 `RootfsManager` 空间检查；失败只 Toast 不闪退
- 终端：`outputRaw` 缓冲 + 搜索过滤渲染，超 200KB 截头；自动滚底
- 作用域 `MainScope()`；`onDestroy` 取消作用域/job、cancel 下载 Call、destroy shell

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

顶层 `{"version": 2, "distros": [ { id/name/description/icon, versions: [...] } ]}`，版本字段均必填，`mirrors`/`sha256` 可为空：

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
grep -r "com.example" app/src/main/java/   # 必须为空
ls -l gradle/wrapper/                       # jar + properties 齐全
git remote -v                               # 必须是 git@github.com:LiStudioorg/linuxandroid.git
test -f app/src/main/jniLibs/arm64-v8a/libproot.so && echo local-so-ok
./gradlew assembleDebug
```

## 8. 硬性约束（禁止违反）

1. UI 使用 **AppCompat + Material Components**（DrawerLayout / NavigationView / RecyclerView / MaterialCardView / MaterialToolbar，浅色主题）；布局 XML + Kotlin，不引入 Compose。
2. 不申请存储权限；不读写应用私有目录以外路径。
3. Manifest 必须 `android:extractNativeLibs="true"`；Gradle 必须 `packaging { jniLibs { useLegacyPackaging = true } }`。
4. 仅 `arm64-v8a`。
5. 包名三处一致：`com.li63050a.linuxandroid`（namespace / applicationId / Kotlin package）。
6. 禁止使用 `java.nio.file`；`Process.isAlive/waitFor(timeout)/destroyForcibly` 必须 SDK_INT 分支。
7. 下载必须 `.part` + Range；解压必须先 tmp 再 rename；符号链接、权限位、路径穿越三者缺一不可。
8. `PROOT_TMP_DIR`、`PROOT_LOADER`、`LD_LIBRARY_PATH` 必须设置为绝对路径。
9. `jniLibs/**/*.so` **必须入库**（`.gitignore` 不得排除；`assets/native/**` 运行库也必须入库）。
10. 新增发行版/历史版本只改 `rootfs_manifest.json` 嵌套结构，不动下载/解压核心逻辑；删除自定义发行版 FAB 功能已废弃，禁止加回。
11. 远程仓库只用 SSH：`git@github.com:LiStudioorg/linuxandroid.git`。
12. 日志分享必须 FileProvider `content://`，禁止 `file://`；闪退前堆栈必须先写 `AppLogger` 再结束进程。
