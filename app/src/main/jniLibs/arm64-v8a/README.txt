PRoot 二进制放置说明（arm64-v8a）
==================================

请将以下两个文件放入本目录（与 README.txt 同级）：

  libproot.so         PRoot 主程序（aarch64 ELF，可执行）
  libproot-loader.so  PRoot loader（aarch64 ELF）

获取方式（Termux 官方仓库，以 5.1.107.94 为例）：

  1. 打开 https://packages.termux.dev/apt/termux-main/pool/main/p/proot/
  2. 下载最新的 proot_*_aarch64.deb
  3. 解开 .deb（dpkg-deb -x，或 7-Zip / ar x data.tar.*）
     - 主程序:   data/data/com.termux/files/usr/bin/proot
     - loader:   data/data/com.termux/files/usr/libexec/proot/loader
       （部分旧版本位于 usr/lib/proot-loader，以 deb 实际内容为准）
  4. 将 proot 重命名为 libproot.so，loader 重命名为 libproot-loader.so
  5. 两个文件一起放进 app/src/main/jniLibs/arm64-v8a/

注意：
1. 必须是 aarch64 / ELF64（readelf -h 可验证 Class: ELF64, Machine: AArch64）。
2. libproot.so 另依赖 libtalloc.so.2 与 libandroid-shmem.so，
   已随仓库放在 app/src/main/assets/native/arm64-v8a/，
   运行时由 NativeDeps 释放并通过 LD_LIBRARY_PATH 注入，无需放入本目录。
3. 本目录 *.so 已随仓库入库（.gitignore 未排除，git ls-files 可见），
   CI 直接打包，无需再从 Termux 仓库下载；若 workflow 中存在
   “Fetch PRoot binaries from Termux deb” 步骤，文件已存在则跳过（兜底）。
4. AndroidManifest 已配置 extractNativeLibs=true，
   app/build.gradle.kts 已配置 packaging.jniLibs.useLegacyPackaging=true，
   安装时会将本目录 so 解压到 nativeLibraryDir 并可直接执行。
