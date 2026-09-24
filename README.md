# LinuxAndroid（ProotTerm）— 免 root 的 Android Linux 终端

在 Android 应用内运行无图形界面的 Linux 环境：基于 **PRoot（Termux 适配版）**，通过简易文本终端输入命令、查看输出。无需 root，不申请存储权限，全部数据位于应用私有目录。

- 包名 / applicationId：`com.li63050a.linuxandroid`
- 版本：`0.0.0.1`（versionCode 1）
- 支持系统：**Android 7.0（API 24）～ Android 16（API 36）**
- 仅支持 **arm64-v8a（64 位 ARM）**

## 功能

- 现代化浅色 Material 界面：**侧边栏抽屉**导航（终端 / 发行版管理 / 设置），默认进入终端
- 终端页：顶部搜索框过滤输出、深色等宽终端区（自动滚底）、圆角命令输入与发送按钮
- 发行版管理：**卡片列表**（Alpine / Debian / Ubuntu），圆形字母图标 + 大小描述 + 操作按钮；下载中卡片内显示进度条与百分比
- 一键下载，实时进度；**断点续传**（`.part` + HTTP Range）；**多镜像自动回退**
- SHA256 校验（清单有值才校验）
- 解压保留符号链接与 rwx 权限位，防路径穿越；先临时目录、成功后**原子重命名**再写 `.installed`
- 安装完成卡片按钮变“启动”，PRoot 拉起持久 shell；设置页含版本信息与 GitHub 仓库链接

## PRoot 二进制从哪来

### 方式一（推荐）：CI 自动下载

`.github/workflows/android.yml` 在编译前执行 `Fetch PRoot binaries from Termux deb`：

1. 下载 `proot_5.1.107.94_aarch64.deb`
   （仓库：<https://packages.termux.dev/apt/termux-main/pool/main/p/proot/>）
2. `dpkg-deb -x` 解开（或 7-Zip / `ar`）
3. 拷贝两份文件到 `app/src/main/jniLibs/arm64-v8a/`：
   - `data/data/com.termux/files/usr/bin/proot` → **`libproot.so`**
   - `data/data/com.termux/files/usr/libexec/proot/loader` → **`libproot-loader.so`**
     （个别旧版本在 `usr/lib/proot-loader`，以 deb 实际内容为准）
4. `chmod 755`

因此 `*.so` **不入库**（见 `.gitignore`）也能正常出包；文件已存在则自动跳过。

### 方式二：本地手动放置

在 Android Studio / 本地构建 `assembleRelease` 前，按上文 1–4 步手动把两个文件放进 `jniLibs/arm64-v8a/`。

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

## Git 远程（SSH）

已绑定：

```bash
git remote -v
# origin  git@github.com:LiStudioorg/linuxandroid.git (fetch/push)
```

推送：`git push -u origin master`（或先 `git branch -m main`）。

## 使用方法

1. 侧边栏进入「发行版管理」，点击卡片“下载” → 进度显示在卡片内（可中断，再次点击**断点续传**，主源失败自动换镜像）
2. 校验 SHA256 → 解压 → 卡片按钮变“启动”
3. 点击“启动”自动跳转「终端」页；输入命令（如 `ls`、`cat /etc/os-release`）点“发送”或键盘发送键
4. 顶部搜索框可过滤终端输出行；「设置」页可查看信息、打开 GitHub、清空终端
5. rootfs 位于 `filesDir/rootfs/<id>`，重装＝清除应用数据

```sh
cat /etc/os-release     # 查看发行版
sh -i                   # 无提示符时进入交互 shell
apk update && apk add vim        # Alpine
apt update && apt install -y vim # Debian / Ubuntu
exit
```

## 常见问题

| 现象 | 处理 |
| --- | --- |
| 无提示符、输入不回显 | 非 PTY 属正常，命令已执行；输入 `sh -i` / `bash -i` |
| 启动提示“缺少 PRoot” | 按上文放入两个 `.so` 后重新打包 |
| 点发送无反应 / 秒退 | 多半是 APK 缺 `libproot.so`（没走 CI 拉取也没手动放）或缺运行库，重装完整产物 |
| 下载中断 | 再点“下载”，`.part` 自动续传 |
| SHA256 失败 | 自动删除坏文件，重新下载即可 |
| 空间不足 | 下载+解压建议预留 500MB |

## 工作原理（简述）

```
assets/rootfs_manifest.json
  → OkHttp Range 下载至 filesDir/<id>.<format>.part（多镜像回退）
  → SHA256 校验
  → Commons Compress 解压至 filesDir/tmp_<id>（符号链接/权限/防穿越）
  → 删旧目录，rename 为 filesDir/rootfs/<id>，写 .installed
  → NativeDeps 释放 libtalloc/libandroid-shmem → filesDir/native
  → libproot.so -r <rootfs> -0 -w /root -b /dev -b /proc -b /sys \
      -b /dev/urandom:/dev/random <shell>
      （PROOT_TMP_DIR / PROOT_LOADER / LD_LIBRARY_PATH 全部绝对路径）
```

编码约定与完整方案见 [AGENTS.md](AGENTS.md)。

## 声明

PRoot 版权归其原作者；各发行版 rootfs 遵循各自许可证。本项目代码仅供学习与研究。
