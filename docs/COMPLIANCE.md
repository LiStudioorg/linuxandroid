# 架构合规审查报告

> 生成方式：对工作区当前内容（HEAD `12d6350` + 本轮功能改造）做静态核查，逐条比对 [AGENTS.md](../AGENTS.md) 第 8 节「硬性约束」。
> **本报告不含编译验证**：当前环境无 JDK、无 Android SDK，`./gradlew assembleDebug` 未能执行（详见第 3 节）。
> 配套文档：[ARCHITECTURE.md](ARCHITECTURE.md)（架构与链路）、[MODULES.md](MODULES.md)（模块契约）。

---

## 1. 结论速览

| 项 | 结果 |
| --- | --- |
| 约束条目总数 | 16（本轮由 12 条扩至 16 条，新增 §8.13–§8.16） |
| 静态核查通过 | 15 |
| **存在偏差** | **1**（§8.3 字面不符，已按维护者决定保留实现并同步文档措辞；§8.11 已按维护者决定保留 HTTPS） |
| 未能验证 | 构建可复现性、运行期 PRoot 行为（缺工具链与设备） |

本轮新增的四条约束（§8.13–§8.16）**全部由本轮代码实现并静态核查通过**，不是事后补的规则。

---

## 2. 逐条核查

| # | 约束 | 结果 | 证据 |
| --- | --- | --- | --- |
| 8.1 | AppCompat + Material，不引入 Compose | ✅ | `app/build.gradle.kts` 仅 appcompat/material/drawerlayout/recyclerview/coordinatorlayout；全仓无 `androidx.compose` 依赖、无 `@Composable`、无 `setContent`。布局为 XML（12 个 `res/layout/*.xml`），新增 `view_myapps.xml`、`item_myapp.xml` |
| 8.2 | 不申请存储权限、不用 SAF；只允许两个私有沙箱 | ✅ | `AndroidManifest.xml` 仅 `INTERNET`/`RECEIVE_BOOT_COMPLETED`/`FOREGROUND_SERVICE`/`FOREGROUND_SERVICE_DATA_SYNC`/`POST_NOTIFICATIONS`；`grep -rn "EXTERNAL_STORAGE" app/src/main/` 无命中；`grep -rn "ACTION_OPEN_DOCUMENT"` 无命中。两个沙箱见 `StorageLocation.sandboxDir()`（`filesDir` / `getExternalFilesDir(null)`），无第三处路径 |
| 8.3 | Manifest 显式 `extractNativeLibs="true"` | ⚠️ **偏差** | Manifest 中**已无**该属性；改为 `app/build.gradle.kts` 的 `packaging { jniLibs { useLegacyPackaging = true } }`。字面偏差，效果等价（见 §4.1） |
| 8.4 | 仅 arm64-v8a | ✅ | `abiFilters += "arm64-v8a"`；`jniLibs/` 与 `assets/native/` 均只有 `arm64-v8a/` 一个 ABI 目录 |
| 8.5 | 包名三处一致 | ✅ | `namespace`、`applicationId`、各 .kt 首行 `package` 均为 `com.li63050a.linuxandroid`；`grep -rn "com.example" app/src/main/java/` 无命中 |
| 8.6 | 禁 `java.nio.file`；Process API 走 SDK 分支 | ✅ | `grep -rn "java\.nio\|toPath()" app/src/main/java/` 无命中。`ProotSession` 中 `isAlive`/`waitFor(timeout)`/`destroyForcibly` 各一处，均在 `SDK_INT >= O` 分支内；本轮新增的 `MainActivity.isSymlink` 改用 `Os.lstat` + `(mode and S_IFMT) == S_IFLNK`，同样规避 |
| 8.7 | `.part` + Range；先 tmp 再 rename；链接/权限/防穿越 | ✅ | `RootfsDownloader`（Range/416/206/200 分支）；`RootfsManager.finalizeInstall`（删旧→`.old` 备份→rename→写标记，失败可回滚）；`RootfsExtractor`（穿越校验、`Os.symlink`、`Os.chmod`、`lstat`+`remove`）。本轮 `tempDir`/`partFile`/`rootfsRoot` 全部改为经 `sandboxDir` 推导，**三者同源同分区**，`renameTo` 不会跨文件系统 |
| 8.8 | 三个环境变量绝对路径 | ✅ | `ProotSession` 用 `File.absolutePath` 传 `PROOT_TMP_DIR` / `PROOT_LOADER` / `LD_LIBRARY_PATH`；本轮 `prootTmpDir()` 固定为内部 `filesDir/proot_tmp` |
| 8.9 | `.so` 必须入库 | ✅ | `git ls-files` 确认 4 个二进制均被跟踪；`.gitignore` 未排除 |
| 8.10 | 新增发行版只改清单 | ✅ | `ManifestLoader` 纯数据驱动，无硬编码家族/版本分支 |
| 8.11 | 远程仓库协议与文档一致 | ✅ | `git remote -v` 实测为 `https://github.com/LiStudioorg/linuxandroid.git`。经维护者确认保持 HTTPS |
| 8.12 | 分享用 `content://`；崩溃先写日志 | ✅ | `LogActivity` 用 `FileProvider.getUriForFile` + `FLAG_GRANT_READ_URI_PERMISSION`；全仓无 `file://` 分享；`App` 崩溃处理器先 `AppLogger.e` 再委托默认处理器 |
| **8.13** | **shell 不得由 Activity 持有** | ✅ | `MainActivity.onDestroy` 只取消 `installJob`/`downloader`/`scope`，**不含** `SessionManager.stop(id)`；`onStop` 仅 `SessionManager.detach(this)`。会话所有者是 `SessionManager`（多实例注册表）+ `ProotService`（前台服务）。`ProotService.onDestroy` **也不是**强制结束点——它只取消 `scope`/`startJobs`（与约束原文一致：唯一权威结束点是 `ACTION_STOP`，可带 `EXTRA_INSTANCE_ID`） |
| **8.14** | **`PROOT_TMP_DIR`/`logs`/`native` 固定内部** | ✅ | `RootfsManager.prootTmpDir()`/`logsDir()`/`nativeDir()` 三个方法**不读 `location`**，一律基于 `filesDir`；`RootfsManager` 中的 `@Volatile var location` 不影响它们；`AppLogger.init(filesDir)` 注释已明确该解耦 |
| **8.15** | **命令输入框避开安全键盘** | ✅ | `view_terminal.xml` 的 `edit_command` 用 `style="@style/App.TerminalInput"` + `importantForAutofill="no"`；`themes.xml` 中该 style 为 `textVisiblePassword\|textNoSuggestions` + `imeOptions=actionNone\|flagNoExtractUi\|flagNoFullscreen`；`grep -n "textPassword\|textWebPassword" view_terminal.xml` 无命中；`MainActivity.hardenImeForTerminal()` 再在运行期覆盖一次 |
| **8.16** | **快捷键发真实控制序列** | ✅ | `MainActivity` 中 `KEY_ESC="\u001b"`、`KEY_UP/DOWN/LEFT/RIGHT`、`KEY_HOME="\u001b[H"`、`KEY_END="\u001b[F"`、`KEY_PGUP/PGDN`；`TAB` 发 `\t`；CTRL 发 `(c - 'a' + 1)`；ALT 发 `"\u001b" + c`。全部经 `SessionManager.sendRaw(id, data)` 写入 `ProotSession` 的 stdin，无一为空实现 |
| **8.17** | **多实例隔离** | ✅ | `InstanceConfig`（`data class`，含 `limits`/`mirrorOverride`/`preferAutoMirror`/`downloadThreads`/`aptMirror`）+ `InstanceStore` 持久化到 `filesDir/instances.json`，**不写 `AppPrefs`**；`SessionManager` 用 `ConcurrentHashMap<String, SessionSlot>`，每槽独立 `AtomicInteger epoch`/`state`/`session`/`lastError`。**无参「当前会话」访问器已不存在**：`stateOf(id)`/`isRunning(id)`/`snapshot(id)`/`sendCommand(id,…)` 全部强制传 id。`SessionHolder.kt` 已删除，全仓 0 引用 |
| **8.18** | **资源限制真正下发（ulimit）** | ✅ | `ResourceLimits.toGuestCommand(shell)` 返回 **3 个独立 argv** `[/bin/sh, -c, 脚本]`，`ProotSession` 用 `addAll(guestArgs)` 追加到 proot 命令行——**不是**单个字符串（proot 不调用 shell，单字符串会被当文件名解析而启动失败，见 §5.3）。脚本末尾 `exec "$shellPath"` 保证真 shell 替换包装进程。`-v`/`-s` 做 MB→KB `×1024`；`-t` 为 CPU **秒**，UI 文案（`limits_cpu_hint`）如实说明非核数；`0` 不生成语句（`isUnlimited` 时 `toScript` 返回 `null`） |
| **8.19** | **下载器可靠性三约束** | ✅ | 线程数 `coerceIn(1, MAX_THREADS=8)` 有上限；`MultiPartDownloader.downloadChunk` **强制要求 HTTP 206**，否则抛「服务端不支持 Range」，并由 `download()` 捕获后降级单线程（`single.download`）；合并前校验 `merged.length() == total`，不符即删并抛错；取消/失败/卸载三处都调 `cleanupChunks`（含 `.src` 标记）。额外强化：分块复用需**长度 + 来源标记**双重匹配（`chunkToken = "$url\|$total"`），防止跨镜像拼出「长度正确但内容错误」的归档 |
| **8.20** | **镜像优选与自定义源** | ✅ | `MirrorProbeService.probe` 用 `async` 并发 + `withTimeoutOrNull`，单镜像 `PER_MIRROR_TIMEOUT_MS=4_000`、整体 `TOTAL_TIMEOUT_MS=6_000`；全失败返回 `emptyList()`，`rank()` 原样返回候选顺序，**不阻断下载**。`InstanceStore.normalizeMirror` 校验 scheme 仅 http/https，非法返回 `null`。SHA256 仍是唯一信任来源（测速仅排序）。HEAD 失败时的 `Range: bytes=0-0` 回退**显式关闭 body**，避免连接池泄漏 |
| **8.21** | **APT 换源幂等可逆** | ✅ | 备份后缀 `.prootterm.bak`（`BACKUP_SUFFIX`），`backupOnce` **已存在则不覆盖**；`restoreOriginal` 找不到备份时返回 `Failure` 而非假装成功；deb822（`/etc/apt/sources.list.d/*.sources`，保留 `Signed-By`）与旧式 `sources.list` 双格式；Alpine 单独走 `/etc/apk/repositories`。代号从 `os-release` 读，兜底链 `VERSION_CODENAME`→`UBUNTU_CODENAME`→`VERSION`/`PRETTY_NAME` 括号 →`stable`。`writeAtomic`/`copyAtomic` 均为临时文件 + rename |

---

## 3. 未验证项

**构建未执行。** 环境缺失：

- `java` 不在 `PATH`
- `ANDROID_HOME` / `ANDROID_SDK_ROOT` 均未设置
- 无 Android 设备（无法验证 PRoot 实际拉起 guest shell、安全键盘是否真的不弹）

因此以下结论**仅来自静态阅读，未经编译或运行验证**：

- Kotlin 编译是否通过（本轮新增 6 个类、改写 6 个类，改动量大）
- AAPT 资源链接是否通过（新增 `view_myapps.xml`、`item_myapp.xml`、`bg_badge.xml`，`MaterialSwitch` 需 Material 1.7.0+）
- merged Manifest 是否正确合成 `android:extractNativeLibs="true"` 与 `foregroundServiceType="dataSync"`
- API 24 设备上各 `SDK_INT` 分支是否都走到正确的低版本路径
- 外部存储位置（`/Android/data`）在 Android 11+ 上是否真能执行 proot

建议在具备 JDK 17 + Android SDK 36 的机器上补跑：

```bash
./gradlew assembleDebug
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep -E '\.so'
# 并确认合并后的 Manifest
unzip -p app/build/outputs/apk/debug/app-debug.apk AndroidManifest.xml | strings | grep -i extractNativeLibs
```

---

## 4. 两条偏差的处置建议

### 4.1 §8.3 `extractNativeLibs`（建议：**改文档，不改代码**）

**事实**：`12d6350` 提交显式从 Manifest 删除了 `android:extractNativeLibs="true"`，提交信息写明「extractNativeLibs 改由 Gradle DSL 管理」。

**为什么功能上没问题**：AGP 8.x 中 `packaging.jniLibs.useLegacyPackaging = true` 会让构建期在生成的 Manifest 里合成 `android:extractNativeLibs="true"`。**最终 APK 的合并 Manifest 仍带该属性**，`libproot.so` 仍会被解压到 `nativeLibraryDir` 并可执行 —— 这正是 `SessionManager.startBlocking` 依赖的前提（从 `applicationInfo.nativeLibraryDir` 取 proot）。

**建议措辞**（AGENTS.md §8.3 已按此调整）：

> 必须保证 `libproot.so` 安装后被解压到 `nativeLibraryDir`：由 `packaging { jniLibs { useLegacyPackaging = true } }` 保证（AGP 会自动合成 `android:extractNativeLibs="true"`）。若单独在 Manifest 显式声明该属性亦可，但两者不得相互矛盾。

若坚持字面一致，**必须同时**保留 `useLegacyPackaging = true`，不能只加 Manifest 属性。

### 4.2 §8.11 Git 远程（已按维护者决定处理：**保留 HTTPS，同步文档**）

**事实**：`origin` 是 `https://github.com/LiStudioorg/linuxandroid.git`（HTTPS）。维护者确认**保留 HTTPS**（推送凭据方式不变、无需本机 SSH key），因此同步文档而非改仓库配置。

---

## 5. 代码层面值得关注的点（非约束违反）

### 5.1 本轮修复

| # | 问题 | 修复方式 |
| --- | --- | --- |
| 1 | **shell 无法脱离 Activity 存活** —— `onDestroy` 无条件 `session.destroy()`，「后台保持运行」与「开机自启动」均无法实现 | 引入 `SessionManager`（进程内单例，持有状态机与观察者）+ `ProotService`（前台服务，常驻通知）。`ProotSession` 重构为 **Activity 无关**，`MainActivity` 退化为观察者；§8.13 固化为硬约束 |
| 2 | **输出刷爆日志** —— 每个 stdout 分片都写 `AppLogger`，交互式命令几分钟就能滚满 1MB 环形缓冲，把启动失败等关键信息挤掉 | 移除 `ProotSession` 中的逐块 `AppLogger.d("ProotOut", ...)`；只记启动命令、环境变量、退出码 |
| 3 | **外部存储不支持导致启动失败后无解释** | 新增 `ProotSession.explainStartFailure()`，把 permission denied 翻译成「请把存储位置改为内部存储后重新安装」 |
| 4 | **`installed elsewhere` 被误判成「未安装」** —— 切换存储位置后，装在另一位置的版本点「启动」只会报「尚未安装」 | `RootfsManager.isInstalledElsewhere()` + `VersionAdapter.State.InstalledElsewhere`，按钮改为「重新下载」并显示位置标签 |
| 5 | **输出缓冲存在数据竞争** —— `StringBuilder` 标注为主线程限定，但 `snapshot()`/`clearOutput()` 可被任意线程调用 | `snapshot()` 在非主线程时 `mainHandler.post` + `CountDownLatch.await(2s)`；`clearOutput()` 同样 marshal 到主线程；`truncatedChars` 加 `@Volatile` |
| 6 | **`isSymlink` 的 Kotlin 中缀优先级陷阱** —— `mode and S_IFMT == S_IFLNK` 会被解析为 `mode and (S_IFMT == S_IFLNK)`，无法编译 | 拆成 `val mode = Os.lstat(...).st_mode` 后 `(mode and S_IFMT) == S_IFLNK` |
| 7 | **死文案 / 硬编码中文** —— `autostart_need_target`、`notif_*`、`state_installed` 未使用；通知文案硬编码在 Kotlin 里 | 通知文案迁入 `strings.xml`，经 `SessionManager.init(context)` + `str()` 读取；`state_installed` 用于 `MyAppAdapter`；最终审计零未使用、零未定义字符串 |

#### 第二轮（多实例 / 资源限制 / 镜像 / 换源）—— 经独立对抗性审查后修复

| # | 问题 | 修复方式 |
| --- | --- | --- |
| 1 | 🔴 **设了资源限制的会话一律启动失败** —— `ulimit …; exec "…"` 被当作**单个 argv** 传给 proot。proot 不像 `env` 那样替你调用 shell：`src/path/path.c` 的 `which()` 把最后一个参数当**文件路径** `realpath`，失败即 `'…' not found (root = …, $PATH=…)`；随后 `launch_process()` 直接 `execvp`。**已在本仓 `libproot.so` 中确认该报错字符串存在，且二进制内无 `-c` 字符串**，即 proot 无任何 shell 包装 | `ResourceLimits` 拆成 `toScript()`（脚本文本）+ `toGuestCommand()`（返回 `[/bin/sh, -c, 脚本]`），`ProotSession` 用 `addAll(guestArgs)`。**同时删除**危险的 `toShellPrefix()`，避免后人再次当成单 argv 使用 |
| 2 | 🔴 **「结束会话」会杀掉所有实例** —— `showExitDialog` 与存储切换两处都先发带 id 的 `stopIntent`，**紧接着**又发一个不带 id 的 `stopIntent(this)`；后者语义是全停 | 两处都改为只按 id 结束。存储切换改为遍历 `SessionManager.runningIds()` 逐个 id 结束（且只结束确实在旧位置/焦点实例） |
| 3 | 🟠 **分块复用仅凭长度** —— 分块文件跨重启、跨镜像留存，而镜像顺序按延迟排序**不稳定**；不同镜像的同一文件字节数相同但内容不同时，旧块会被直接合并，总长校验也能通过。Debian/Ubuntu 的 `sha256` 为空，**没有任何完整性网兜底** | 新增来源标记文件 `<...>.part.<N>.src`，内容为 `"$url\|$total"`；复用需**长度 + 标记**双重匹配，不符即删重下；`cleanupChunks` 一并清理标记 |
| 4 | 🟠 **`MirrorProbe` 回退 GET 未关闭 body** —— HEAD 失败时走 `Range: bytes=0-0`，只看 `code` 就离开 `use`，OkHttp 不会归还连接，反复测速会累积占用连接/调度槽 | 在 `use` 内显式 `resp.body?.close()` |
| 5 | 🟡 **`InstanceStore` 缓存无锁且 UI 与服务各持一份** —— `ProotService` 从 IO 线程读，`MainActivity` 从主线程读写；缓存是裸 `var LinkedHashMap`，`persist()` 迭代时可能被 `put` 修改。且两处各 `new` 一个实例，**UI 保存的限制服务可能读不到（表现为「保存了不生效」）** | 缓存加 `@Volatile` + `synchronized` 双重检查；`persistLocked()` 在锁内先快照再序列化；新增 `InstanceStore.of(context)` 进程内共享实例，两处调用点均改用它 |
| 6 | 🟡 **换源代号兜底是死代码** —— `x.ifBlank { x }` 读同一个键，取不到时静默退化为字面量 `stable`；而 Ubuntu 不认识 `stable`，`apt update` 直接 404 | 改为兜底链 `VERSION_CODENAME` → `UBUNTU_CODENAME` → 从 `VERSION`/`PRETTY_NAME` 括号内解析 → `stable` |
| 7 | 🟢 **`startJobs` 可能删掉更新的 job** —— `invokeOnCompletion { startJobs.remove(id) }` 无条件移除，若旧 job 在新 job 注册后才回调，会删掉新 job 的追踪 | 改用两参 `startJobs.remove(id, job)` |
| 8 | 🟢 **未使用 import** | `ProotService` 的 `java.io.File`、`MultiPartDownloader` 的 `kotlinx.coroutines.ensureActive` 与 `java.io.RandomAccessFile` 已删除 |

### 5.2 保留待办

按影响排序：

1. **`MainActivity` 已增长到约 1700 行**（本轮新增实例设置对话框、镜像测速、APT 换源、多实例焦点管理）—— 承担五屏导航、三个 RecyclerView 装配、快捷键绑定、IME 加固、设置填充、存储位置切换、自启动目标选择、安装编排、镜像编排、换源、会话启动、多实例焦点切换、输出渲染、返回键与对话框二十余类职责。本轮新增已经让这个问题从「偏低」变成「**需要处理**」。建议按职责拆出 `TerminalController` / `InstallCoordinator` / `ScreenNavigator` / `SettingsController` / `InstanceSettingsController`。

2. **外部存储位置可能无法真正执行 proot**（[ARCHITECTURE.md §19](ARCHITECTURE.md) 第 17 条）—— `/Android/data` 在 Android 11+ 由 FUSE 提供，通常挂载为 `nosuid,nodev,noexec` 语义。已通过 `explainStartFailure()` 给出可操作提示，但**该选项在部分机型上可能始终不可用**。彻底的解法是把 rootfs 放到 `filesDir`（内部）而只把大文件缓存放外部，属架构调整。

3. **切换存储位置不迁移已装系统**（[ARCHITECTURE.md §19](ARCHITECTURE.md) 第 18 条）—— 有意为之（跨文件系统 `renameTo` 不可靠，逐文件复制几十万小文件既慢又可中断），但用户需重新下载。已用常驻提示条 + `State.InstalledElsewhere` 缓解误解。若要支持迁移，应做成显式「迁移」按钮 + 前台服务 + 进度通知，而不是隐式搬运。

4. **系统回收前台服务后，运行中的会话会短暂失去通知**（[ARCHITECTURE.md §19](ARCHITECTURE.md) 第 16 条）—— §8.13 要求 `onDestroy` 不杀会话，服务被 `START_STICKY` 以空 intent 重建后会自行 `stopSelf`，此时无法重新挂出通知。唯一的结束途径是重新打开应用。**这是约束的固有取舍，不是缺陷**：宁可短暂缺通知，也不能因服务被回收就杀掉用户正在跑的任务。若要改善，可在 `App.onCreate` 检测「`state != IDLE` 但无前台服务」并主动重建。

5. **`VersionAdapter.setState` 对不在当前列表的 id 也会写入状态** —— 先写 `states[id]` 再判 `index < 0`。属有意为之（切换家族后状态不丢），但依赖 `id` 全局唯一这一隐含约定。

6. **`RootfsExtractor` 硬链接重试无次数上限** —— 靠 `progress` 标志在「整轮无进展」时收敛，逻辑上必然终止，但极端情况下最坏 O(n²) 次 `exists()` 探测。

7. **Debian 三个版本 `sha256` 为空** —— 跳过完整性校验，仅依赖 HTTP `Content-Length` 比对。需从上游补齐哈希。

8. **`directBootAware` 未开启** —— 有意不处理 `LOCKED_BOOT_COMPLETED`（此时应用目录仍处于凭据加密锁定状态，rootfs 不可读）。若将来要支持「开机即用」，需把 rootfs 放入 device-encrypted 存储并声明 `directBootAware`。

9. **部分 OEM ROM 会拦截 `BOOT_COMPLETED`** —— MIUI/EMUI/ColorOS 等需用户额外授予「自启动」权限。已在设置页文案中提示，但应用侧无法绕过。

10. **`ABORTED` 是公开常量，存在被误用的可能** —— 任何调用方若写成 `if (err != null) 视为失败` 而不先比对 `ABORTED`，就会把「取消」当「失败」处理，重新引入「服务空转」的问题。当前两个调用点（均在 `ProotService`）都正确。可考虑改用密封类返回值让编译器强制区分。

11. **`AppLogger.ensureLogFile()` 的 tmpdir 兜底分支** —— 未 `init` 时落到 `java.io.tmpdir`，在 Android 上该路径不可靠。正常流程不会走到（`App.onCreate` 必先 `init`），属潜在陷阱。

---

## 6. 复核命令

```bash
cd /home/xgp2012/linuxandroid

# 8.2 权限（应仅 5 条：INTERNET / RECEIVE_BOOT_COMPLETED / FOREGROUND_SERVICE*
#     / POST_NOTIFICATIONS）
grep -n "uses-permission" app/src/main/AndroidManifest.xml
grep -rn "EXTERNAL_STORAGE" app/src/main/ || echo "无存储权限 ✓"
grep -rn "ACTION_OPEN_DOCUMENT" app/src/main/ || echo "无 SAF ✓"

# 8.4 ABI
ls app/src/main/jniLibs/ app/src/main/assets/native/
file app/src/main/jniLibs/arm64-v8a/*.so        # 应均为 ELF64 AArch64

# 8.5 包名
grep -n "namespace\|applicationId" app/build.gradle.kts
grep -rn "com.example" app/src/main/java/ || echo "无 com.example ✓"

# 8.6 禁用 API
grep -rn "java\.nio\|toPath()" app/src/main/java/ || echo "无 java.nio ✓"
grep -n "SDK_INT" app/src/main/java/com/li63050a/linuxandroid/ProotSession.kt

# 8.9 .so 入库
git ls-files | grep -E '\.(so|so\.2)$'

# 8.11 远程
git remote -v

# 8.13 会话归属：两个 onDestroy 都不得**调用** SessionManager.stop(id)
#      （必须先剔除注释行，否则解释为什么不能调用的注释会被误判为违规）
strip_c() { sed 's://.*::' "$1" | sed '/^\s*\*/d'; }
for f in MainActivity ProotService; do
  strip_c "app/src/main/java/com/li63050a/linuxandroid/$f.kt" \
    | sed -n '/override fun onDestroy/,/^    }$/p' \
    | grep -q 'SessionManager.stop(id)' \
    && echo "违规：$f.onDestroy 在杀会话" || echo "$f.onDestroy 不杀会话 ✓"
done

# 8.13b prepare() 必须先销毁旧会话
grep -n -A8 "fun prepare" app/src/main/java/com/li63050a/linuxandroid/SessionManager.kt
#   应能看到 session?.let { it.destroy() } 与 session = null

# 8.13c epoch 代次校验（fork 前后各一次）
grep -n "epoch" app/src/main/java/com/li63050a/linuxandroid/SessionManager.kt
#   应看到 prepare/stop 各 ++，startBlocking 里两次 != epoch 判断

# 8.14 关键路径固定内部（三个方法体内不应出现 location/sandboxDir）
grep -n -A4 "fun prootTmpDir\|fun logsDir\|fun nativeDir" \
    app/src/main/java/com/li63050a/linuxandroid/RootfsManager.kt

# 8.15 安全键盘
grep -n "textPassword\|textWebPassword" app/src/main/res/layout/view_terminal.xml \
    && echo "违规！" || echo "无安全键盘触发属性 ✓"
grep -n -A8 'name="App.TerminalInput"' app/src/main/res/values/themes.xml

# 8.16 快捷键真实性
grep -n 'KEY_ESC\|KEY_UP\|KEY_HOME\|KEY_PGUP\|ALT_ESC' \
    app/src/main/java/com/li63050a/linuxandroid/MainActivity.kt
```

---

## 7. 审查边界说明

- 审查对象为**工作区当前内容**（HEAD `12d6350` + 未提交的本轮改造），非某个 tag 的发布产物。
- 结论基于源码静态阅读与 git 元数据，**未做动态验证**（无 JDK/SDK/Android 设备）。
- 第 5 节为**改进建议**，不代表违反 AGENTS.md 硬性约束。
- 本轮新增的 §8.13–§8.16 是**先实现、后成文**：约束的措辞是从已落地的代码里反推出来的，因此不存在「文档要求了但代码没做」的情况；相反，§8.13 是为了防止后来者把 `MainActivity.onDestroy` 改回 `SessionManager.stop(id)` 而专门固化的。
