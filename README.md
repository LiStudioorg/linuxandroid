# LinuxAndroid（ProotTerm）— 免 root 的 Android Linux 终端

在 Android 应用内运行无图形界面的 Linux 环境：基于 **PRoot（Termux 适配版）**，通过简易文本终端输入命令、查看输出。无需 root，不申请存储权限，全部数据位于应用私有目录。

- 包名 / applicationId：`com.li63050a.linuxandroid`
- 版本：`0.0.0.1`（versionCode 1）
- 支持系统：**Android 7.0（API 24）～ Android 16（API 36）**
- 仅支持 **arm64-v8a（64 位 ARM）**

## 功能

- 现代化浅色 Material 界面：**侧边栏抽屉**导航（终端 / 发行版管理 / 日志 / 设置），默认进入**发行版管理**
- **三发行版家族 + 多历史版本**：Alpine 3.20/3.19/3.18、Debian 13/12/11、Ubuntu 24.04/22.04/20.04，选家族 → 选版本下载
- 终端页：搜索框过滤输出、**纯黑等宽终端**（自动滚底）、**快捷键行**（ESC / CTRL / TAB / 方向键）、圆角命令输入与发送
- 终端返回键：会话运行中弹「保持运行 / 关闭终端」
- 一键下载，实时进度；**断点续传**（`.part` + HTTP Range）；**多镜像自动回退**
- SHA256 校验（清单有值才校验）
- 解压保留符号链接与 rwx 权限位，防路径穿越；先临时目录、成功后**原子重命名**再写 `.installed`
- **全局日志**（AppLogger）：内存缓冲 + 文件落盘，闪退堆栈自动写入；日志页可刷新/清空/复制/**一键分享**（FileProvider）
- 设置页含版本信息与 GitHub 仓库链接

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

## Git 远程（SSH）

已绑定：

```bash
git remote -v
# origin  git@github.com:LiStudioorg/linuxandroid.git (fetch/push)
```

推送：`git push origin main`（远程为 SSH `git@github.com:LiStudioorg/linuxandroid.git`）。

## 使用方法

1. 侧边栏默认「发行版管理」：点家族卡片进入**版本选择**，选版本点“下载” → 进度显示（可中断，再点**断点续传**，主源失败自动换镜像）
2. 校验 SHA256 → 解压 → 按钮变“启动”
3. 点击“启动”进入「终端」页；输入命令（如 `ls`、`cat /etc/os-release`）点“发送”或键盘发送键
4. 快捷键：ESC / CTRL（+字母键）/ TAB / 方向键；搜索框过滤输出；退出会话时选「保持运行 / 关闭终端」
5. 「日志」页查看/复制/分享 AppLogger 日志；「设置」页查看信息、打开 GitHub、清空终端
6. rootfs 位于 `filesDir/rootfs/<versionId>`，重装＝清除应用数据

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
| 点发送无反应 / 秒退 | 多半是 APK 缺 `libproot.so` 或缺运行库，检查 `jniLibs`/`assets/native` 后重新打包；完整堆栈见「日志」页 |
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
