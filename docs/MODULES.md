# 模块与接口参考（MODULES）

> 面向需要改代码的人：每个类的公开契约、调用关系、约束与坑。
> 架构与链路图见 [ARCHITECTURE.md](ARCHITECTURE.md)，编码约定见 [AGENTS.md](../AGENTS.md)。

---

## 0. 快速索引

> 行数为本文件编写时的工作区实测值（`wc -l`）。

| 类 | 文件 | 行数 | 定位 |
| --- | --- | ---: | --- |
| `App` | `App.kt` | 58 | Application 入口，日志 + 会话管理器初始化 + 崩溃兜底 |
| `AppLogger` | `AppLogger.kt` | 141 | 全局日志（内存 + 文件 + Logcat），**固定内部位置** |
| `LogActivity` | `LogActivity.kt` | 103 | 日志查看/复制/分享 |
| `MainActivity` | `MainActivity.kt` | 1682 | 五屏编排 + 安装流程 + 实例设置；**只观察会话，不拥有会话** |
| `DistroFamily` / `DistroInfo` | `DistroInfo.kt` | 43 | 清单数据模型 |
| `MyApp` / `InstalledScanner` | `MyApp.kt` | 91 | 「我的系统」数据模型 + 已安装系统扫描 |
| `StorageLocation` | `StorageLocation.kt` | 67 | 存储位置枚举（内部 / 外部私有沙箱） |
| `AppPrefs` | `AppPrefs.kt` | 58 | 全局 SharedPreferences：存储位置 / 自启动 / 最近系统 |
| `ManifestLoader` | `ManifestLoader.kt` | 93 | assets JSON → 数据模型（含结构校验） |
| `RootfsManager` | `RootfsManager.kt` | 284 | 按存储位置的目录规划、空间检查、可回滚原子切换 |
| `RootfsDownloader` | `RootfsDownloader.kt` | 192 | OkHttp 断点续传 + 多镜像（候选顺序由调用方显式传入） |
| `MultiPartDownloader` | `MultiPartDownloader.kt` | 390 | 多线程分块下载：206 判定、合并校验、降级与镜像回退 |
| `MirrorProbe` / `MirrorProbeService` | `MirrorProbe.kt` | 166 | 镜像并发测速与排序建议（失败必回退清单顺序） |
| `RootfsExtractor` | `RootfsExtractor.kt` | 208 | tar 解压（链接/权限/防穿越） |
| `AptMirror` / `AptSwitchResult` / `AptSourceSwitcher` | `AptSourceSwitcher.kt` | 473 | APT / apk 换源与恢复（备份不覆盖、原子写） |
| `ProotSession` | `ProotSession.kt` | 398 | 单个 PRoot 进程 + 输出缓冲（Activity 无关，含资源限制下发） |
| `SessionManager` / `SessionSlot` | `SessionManager.kt` | 417 | 多实例会话注册表：按 id 的状态机 / 输出 / 代次校验 / 通知文案 |
| `ProotService` | `ProotService.kt` | 423 | 前台服务：会话保活 + 汇总通知（会话是否该活着的唯一权威） |
| `BootReceiver` | `BootReceiver.kt` | 63 | 开机自启动入口 |
| `ResourceLimits` | `ResourceLimits.kt` | 140 | 实例资源限制（ulimit 语义）+ 预设 + 序列化 |
| `InstanceConfig` / `InstanceStore` | `InstanceConfig.kt` | 268 | 实例级配置与 `filesDir/instances.json` 持久化 |
| `NativeDeps` | `NativeDeps.kt` | 50 | 释放运行期 .so |
| `Sha256Utils` | `Sha256Utils.kt` | 30 | 流式校验 |
| `DistroAdapter` | `DistroAdapter.kt` | 46 | 家族卡片列表 |
| `VersionAdapter` | `VersionAdapter.kt` | 197 | 版本列表 + 状态机（含取消安装 / 装在别处） |
| `MyAppAdapter` | `MyAppAdapter.kt` | 105 | 「我的系统」列表（运行状态是**集合**） |

合计 **6186 行** Kotlin / 26 个文件（不含资源）。

### 阅读顺序建议

改「会话/后台」相关代码：`ProotSession` → `SessionManager` → `ProotService` → `BootReceiver`。
改「存储位置」相关代码：`StorageLocation` → `AppPrefs` → `RootfsManager` → `MainActivity.refreshMyApps/onStorageSelected`。
改「实例级配置」相关代码：`ResourceLimits` → `InstanceConfig` → `InstanceStore` → `MainActivity.showInstanceSettings/saveInstanceSettings`。

---

## 1. `AppLogger` — 全局日志

**契约**

```kotlin
object AppLogger {
    fun init(filesDir: File)                       // App.onCreate 调用一次
    fun d(tag: String, msg: String)
    fun i(tag: String, msg: String)
    fun w(tag: String, msg: String)
    fun e(tag: String, msg: String, tr: Throwable? = null)
    fun snapshot(): String                         // 内存缓冲快照，供日志页
    fun ensureLogFile(): File                      // 保证可分享文件存在，返回 File
    fun clear()                                    // 清空内存 + 删除文件
}
```

**实现要点**

- 三级输出：内存 `ArrayDeque`（上限 `MAX_MEMORY_ENTRIES = 2000` 条）+ `filesDir/logs/app.log` + `android.util.Log`。
- 行格式：`yyyy-MM-dd HH:mm:ss.SSS | LEVEL | Tag | msg`，异常追加 `Log.getStackTraceString`。
- 滚动：写前若 `app.log > 1MB`，先 `delete` 旧的 `app.log.old` 再把当前文件 `renameTo` 之（**保留一份旧日志，不追加到 old**）。
- 全部操作在 `synchronized(lock)` 内，`SimpleDateFormat` 非线程安全故必须持锁。
- 所有写盘都包 `try/catch` 并降级到 Logcat —— **日志失败绝不能抛异常**，因为它被崩溃处理器调用。

**坑**

- `ensureLogFile()` 在 `init` 之前被调用时，会退化到 `System.getProperty("java.io.tmpdir")` 目录。正常路径下不会发生，因为 `App.onCreate` 一定先跑。
- `clear()` 后 `ensureLogFile()` 会用内存缓冲重建文件；由于 `clear()` 同时清了内存，重建结果是空文件。

---

## 2. 数据模型 — `DistroInfo.kt`

```kotlin
data class DistroFamily(id, name, icon, description, versions: List<DistroInfo>)
data class DistroInfo(id, familyId, name, size, url, mirrors, sha256, format, defaultShell)
```

- `DistroInfo.id` 是**目录名**（`rootfs/<id>`）、**状态机键**（`VersionAdapter.states[id]`）与**会话键**（`SessionManager` 的全部 API 都以它为参数）三重身份，必须全局唯一。
- `allUrls` 是派生属性：`url` 非空则在前，`mirrors` 依次追加。它只是**清单顺序**，下载器未必直接用——见 §6.1 的三级候选。
- `sha256` 为空串 ⇒ 跳过校验（Debian 三个版本即如此）。
- `size` 仅用于进度条分母兜底与列表展示，**不参与完整性判断**（完整性只看 HTTP `Content-Range`/`Content-Length`）。

---

## 3. `RootfsManager` — 目录与安装状态

```kotlin
class RootfsManager(context: Context) {
    fun distroDir(id: String): File        // filesDir/rootfs/<id>
    fun tempDir(id: String): File          // filesDir/tmp_<id>
    fun partFile(id: String, format: String): File   // filesDir/<id>.<format>.part
    fun prootTmpDir(): File                // filesDir/proot_tmp
    fun isInstalled(id: String): Boolean   // 判据：rootfs/<id>/.installed 是文件
    fun availableBytes(): Long             // StatFs；失败返回 -1
    fun checkSpaceFor(id: String, compressedSize: Long): String?   // null = 通过
    fun backupDir(id: String): File        // rootfs/<id>.old
    fun finalizeInstall(id: String, temp: File): File
    fun cleanupTemp(id: String)
    fun uninstall(id: String)
    fun deletePartFiles(id: String)        // 两个存储位置一起扫，删所有含 .part 的文件
    fun allSandboxDirs(): Set<File>        // 两个存储位置的沙箱根（去重）
    companion object { const val MARKER = ".installed" }
}
```

**关键语义**

- `isInstalled` 的唯一判据是标记文件，**不是**目录存在性。手工删 `.installed` 即可让 UI 回到「下载」态而不动已解压内容（调试用）。
- `checkSpaceFor` 需要 `compressedSize + compressedSize*4 + 64MB`。`availableBytes() < 0`（StatFs 异常）时**放行**，不阻断下载。
- `finalizeInstall` 可回滚五步：校验 temp 非空 → 清理残留 `backup` → 若 `dest` 存在则 `dest.renameTo(backup)` → `temp.renameTo(dest)` → 删除 backup 并写 `.installed`。任一步失败抛 `IOException`。
  - 注意 `renameTo` 只在**同一文件系统**内是原子的；temp 与 dest 都在 `filesDir` 下，满足前提。
  - `temp.renameTo(dest)` 失败时会把 `backup` 挪回 `dest`，旧安装**不丢**；若回滚也失败（极小概率）会打 error 日志并保留 `rootfs/<id>.old` 供手工恢复。
- `uninstall` 清掉 `rootfs/<id>`、`rootfs/<id>.old`、`tmp_<id>` 以及所有含 `.part` 的缓存文件。

**`deletePartFiles` 的匹配规则（易错）**

⚠️ 判据是「文件名以 `<id>.` 开头」**且**「名字里含 `.part`」，**不写死** `tar.gz`/`tar.xz`，也**不要求 `.part` 结尾**。这样同时覆盖两类残留：

| 文件名 | 是否删除 |
| --- | --- |
| `<id>.tar.xz.part` | ✅ 单线程断点 |
| `<id>.tar.xz.part.3` | ✅ 多线程分块（§6.2） |
| `<id>.tar.xz.part.3.src` | ✅ 分块来源标记（名字含 `.part`） |
| `<id>.tar.gz.part.0` | ✅ 换过压缩格式的旧残留 |

分块残留必须清干净：§6.2 的复用逻辑要求「长度 + `.src` 来源标记」三者同时匹配才算已下完，但仍然不该留垃圾。

**`allSandboxDirs()`**：返回**两个存储位置**（内部 + 外部，同一位置时自动去重）的沙箱根。凡是「清缓存」语义的操作都必须扫两处——否则用户切换存储位置后，旧位置的断点/分块会永远残留。`MultiPartDownloader.cleanupChunksFor` 也依赖它。

---

## 4. 存储位置 — `StorageLocation` / `AppPrefs` / `RootfsManager`

### 4.1 `StorageLocation`

```kotlin
enum class StorageLocation(val key: String) {
    INTERNAL("internal"), EXTERNAL("external");
    fun isAvailable(sandboxDir: File?): Boolean
    fun rootfsRoot(sandboxDir: File): File
    companion object {
        const val DIR_ROOTFS = "rootfs"
        fun fromKey(key: String?): StorageLocation
        fun sandboxDir(context: Context, location: StorageLocation): File?
    }
}
val StorageLocation.label: String       // 文件级扩展属性（定义在 RootfsManager.kt 末尾）
```

| 位置 | 实际路径 | 获取方式 | 需要权限 |
| --- | --- | --- | --- |
| `INTERNAL` | `/data/data/<pkg>` | `context.filesDir` | 否 |
| `EXTERNAL` | `/Android/data/<pkg>` | `context.getExternalFilesDir(null)` | 否 |

- 两者都是**应用私有沙箱**，Android 4.4 起免权限，且豁免 Android 11+ 分区存储——所以 §8.2「不申请存储权限」仍然成立。
- ⚠️ **外部位置在 Android 11+ 上通常无法执行文件**（FUSE 提供，noexec 语义），proot 可能启动失败。这是该选项的真实功能限制，不是 bug，已由 `ProotSession.explainStartFailure()` 给出可操作提示。
- ⚠️ 两个位置都会在**卸载应用时被系统清除**：它们解决的是容量，不是持久性。

### 4.2 `AppPrefs`（全局偏好）

```kotlin
class AppPrefs(context: Context) {
    var storageLocation: StorageLocation
    var autoStart: Boolean
    var autoStartId: String
    var lastVersionId: String
}
```

- SharedPreferences 名 `"prootterm_settings"`。未知的 `storage_location` 值回退 `INTERNAL`。
- ⚠️ **只存全局偏好，不存实例级配置**。资源限制 / 镜像偏好 / 换源记录属于单个实例，必须走 `InstanceStore`（§10），写进这里会让「多实例」名存实亡（§8.17）。

### 4.3 `RootfsManager` 的位置相关契约

```kotlin
class RootfsManager(context: Context) {
    val location: StorageLocation
    val sandboxDir: File              // 解析后的实际根（已处理降级）
    val locationFallback: Boolean     // true = 想要外部但降级到了内部，UI 必须提示
    val isExternal: Boolean
    fun setLocation(wanted: StorageLocation): Boolean
    fun otherLocation(): StorageLocation
    fun rootfsRoot(): File; fun distroDir(id): File; fun tempDir(id): File
    fun partFile(id, format): File; fun backupDir(id): File
    fun prootTmpDir(): File; fun logsDir(): File; fun nativeDir(): File   // 固定内部
    fun isInstalled(id: String): Boolean; fun isInstalledElsewhere(id: String): Boolean
    fun availableBytes(): Long
    fun finalizeInstall(id, temp, shell: String? = null): File
    fun recordedShell(id): String?    // 读安装时写入的 .shell
    fun cleanupTemp(id); fun uninstall(id); fun deletePartFiles(id)
    fun allSandboxDirs(): Set<File>
    companion object { const val MARKER = ".installed"; const val SHELL_MARKER = ".shell" }
}
```

- **`prootTmpDir()` / `logsDir()` / `nativeDir()` 不读 `location`**，一律基于 `filesDir`（§8.14）。
- `tempDir` 与 `distroDir` 同处一个存储位置 ⇒ `renameTo` 不会跨文件系统。
- `finalizeInstall` 的可选 `shell` 参数：把清单声明的 shell 写入 `rootfs/<id>/.shell`，
  这样即使清单之后加载失败，也能准确知道该用哪个 shell，不会把自装系统误报成「自定义 rootfs」。
- **切换存储位置只影响后续安装**，不迁移已装数据（跨文件系统 rename 不可靠）。
  ⚠️ `MainActivity.onStorageSelected` 在切换前会结束会话，但它只结束**焦点实例** + `stopAll()` 兜底，是有意为之的「切换即全部结束」语义。

---

## 5. `RootfsDownloader` — 单线程下载（含断点续传）

```kotlin
class RootfsDownloader(client: OkHttpClient = ...) {
    fun cancel()
    suspend fun download(distro: DistroInfo, partFile: File,
                         urls: List<String> = distro.allUrls,
                         onProgress: (done: Long, total: Long) -> Unit): File
}
```

**`urls` 必须显式传入候选顺序**

> ⚠️ 默认值只是「清单顺序」的兜底。测速优选的结果、用户自定义镜像源都必须由调用方**显式传入**——如果这里改成内部固定取 `distro.allUrls`，那么「算完了排序却没用上」，优选与自定义源会**静默失效**（这正是本轮把 `urls` 提为显式参数的原因）。

**行为矩阵**

| 场景 | 处理 |
| --- | --- |
| 已有 `.part` 且 `length > 0` | 加 `Range: bytes=<n>-` + `Accept-Encoding: identity` |
| `206` + `Content-Range` 起点 == 已有长度 | 追加写入（`append = true`） |
| `206` + 起点 == 0 | 视为服务端忽略 Range，截断覆盖 |
| `206` + 起点其他 | 抛 `Content-Range 起点不匹配`，进下一镜像 |
| `200` | 截断覆盖，`verifyTotal = contentLength` |
| `416` | 视为 `.part` 已完整，回调 `(len, len)` 后 return |
| 非 2xx/416 | 抛 `HTTP <code>` |
| 写完 `done < verifyTotal` | 抛「下载不完整」，进下一镜像 |

- 进度回调**节流 200ms**（`PROGRESS_INTERVAL_MS`），且循环结束时必发一次终值。
- 进度分母 `uiTotal` 优先级：`Content-Range` 总长 > `Content-Length` > 清单 `distro.size` > `-1`。
- 取消：`cancel()` 结束活动 `Call` → `withContext` 内的 `ensureActive()` 抛 `CancellationException` → **不再尝试后续镜像**。
- `activeCall` 是 `@Volatile`，`finally` 中置空。
- 超时：连接 20s / 读写 60s，`retryOnConnectionFailure(true)`。

**坑**

- `cancel()` 只对**当前** Call 生效；若正处于 `for (url in candidates)` 的镜像切换间隙，靠 `ensureActive()` 拦截。
- `.part` 跨镜像复用，因此镜像内容必须与主源**字节一致**（Alpine 官方源与 tuna 镜像满足）。
- `urls` 传空列表时回退到 `distro.allUrls`；候选全空才抛 `IOException("没有可用的下载地址")`。

---

## 6. 镜像优选与多线程下载

### 6.1 三级候选来源（`MainActivity.install` + `MirrorProbeService`）

```
用户自定义镜像源（InstanceConfig.mirrorOverride，仅 http/https）
  → 清单 url（主源）
  → 清单 mirrors[]
```

- 自定义源是**追加**而非替换：清单地址是已知可用的保底，用户填错地址不该让人完全下载不了。
- `preferAutoMirror == true` 且候选 > 1 时才测速；`MirrorProbeService.rank(candidates)` 返回**重排后的完整列表**（探测失败的排在后面保底），永不删地址、永不在失败时抛异常。
- `MainActivity.buildMirrorCandidates(app, customInput)` 是「测速对话框」用的同一套候选构造，两处逻辑必须保持一致。
- 多线程分块**只对首选地址**做：不同镜像的文件不一定字节一致，混合分块会合并出损坏归档。

### 6.2 `MultiPartDownloader` — 多线程分块下载

```kotlin
class MultiPartDownloader(
    single: RootfsDownloader = RootfsDownloader(),
    client: OkHttpClient = ...  // 连接 20s / 读写 60s
) {
    fun cancel()      // cancelled = true + single.cancel()
    fun reset()       // 清取消标记，让同一实例可被下一次下载复用
    suspend fun download(distro, partFile, urls: List<String>, threads: Int,
                         onProgress: (done: Long, total: Long) -> Unit): File

    companion object {
        const val MAX_THREADS = 8
        private const val MIN_MULTIPART_BYTES = 8L * 1024 * 1024   // 8MB
        fun cleanupChunksFor(manager: RootfsManager, id: String)
    }
}
```

**决策流程**

```
reset()
  → probeLength(urls.first()): HEAD 的 Content-Length，失败退化为 distro.size 或 -1
  → 长度 <= 0 | threads <= 1 | 长度 < 8MB  →  cleanupChunks() 后 single.download(distro, partFile, urls, ...)
  → 否则切成 threads 块并发下到 <id>.<fmt>.part.<index>
      → 按序合并到 <part>.merge → 校验总长 == 预期总长 → rename 成 .part → cleanupChunks()
```

**分块文件命名**：`<id>.<format>.part.<index>`（`chunkFile()` = `"${partFile.name}.$index"`），另有一个来源标记 `<...>.<index>.src`（`chunkMarker()`，内容 `"$url|$total"`）。
⚠️ 因此 `RootfsManager.deletePartFiles` 必须用「含 `.part`」而不是「以 `.part` 结尾」匹配（§3）——分块与它的 `.src` 标记都落在这一条规则里，两份代码是一对。

**分块复用（续传）必须同时满足三个条件**

```
1. chunkFile.length() == expected             // 本块应有的长度
2. marker.isFile                              // 存在 .src 来源标记
3. marker.readText() == "$url|$total"         // 来源 URL 与总长度都与上次一致
```

任一不满足就**删除该块重下**，绝不复用。

> ⚠️ **只用长度判断是危险的**：分块文件会跨应用重启、跨镜像留存；而镜像顺序是**按延迟排序的、本身就不稳定**。若换了镜像、新镜像的文件恰好字节数相同但内容不同（同一版本的重新构建），旧的完成块会被当成有效数据直接参与合并——**总长度校验也能通过**，于是产出一个「损坏但长度正确」的归档。Debian/Ubuntu 在清单里 `sha256` 为空，没有完整性网兜底，所以必须在这里挡住。
>
> `cleanupChunks()` 会把 `.src` 一起删：留下孤儿标记虽不会被误用（长度检查会先失败），但会永远占着目录。

**四条必须记住的行为**

| 场景 | 行为 |
| --- | --- |
| 服务端对分块请求返回 **200**（忽略了 `Range`） | **直接抛错**，绝不落盘。200 返回的是整个文件，照写会让这一块包含全量数据，合并后必然损坏 |
| 长度未知 / < 8MB / `threads <= 1` | 降级为 `single.download`，功能不缺失（单线程本身有 Range 续传），只是慢一些 |
| 分块失败 | 清理全部分块 → 用**其余候选镜像**（`urls.drop(1)`）走单线程重试一遍 |
| 其余镜像也全失败 | 抛出**最初**的失败原因（来自优选过的首选地址，信息量更大） |

**其他要点**

- 单线程降级路径**必须原样透传 `urls`**（含测速优选与自定义源），否则 §6.1 的两项工作在最后一步被悄悄丢掉。
- 分块失败**不会**在原地址上自动降级为单线程整份重下：可能是网络瞬断，慢网下重下整份代价太大。改为保留 `.part`，让用户重试时走单线程 Range 续传。上面说的镜像是**换地址**重试，与此不同。
- 已存在的分块若满足上面三个条件才复用（`doneBytes` 累加 + 进度回调 + 日志 `reused`）；不符则记 `discarded (source/length mismatch)` 并删除重下。
- 单块写完 `written != expected` 时**删掉该块**并抛错，避免合并进损坏数据。
- 合并失败（长度不符 / 无法替换 / rename 失败）会删掉 `.merge`，并抛 `IOException`，由调用方清理分块。
- 取消：`cancel()` 置 `cancelled = true`，分块循环每轮与请求前都检查，抛 `CancellationException`；`catch (e: CancellationException)` 分支清理分块后**重抛**（协程语义要求）。
- `cleanupChunksFor(manager, id)` 扫 `manager.allSandboxDirs()`（两个存储位置），只删名字含 `.part.` 的分块，**保留**单线程 `.part`。
  调用点：`MainActivity.install` 的取消/失败分支、`confirmUninstall`。

### 6.3 `MirrorProbe` / `MirrorProbeService` — 并发测速

```kotlin
data class MirrorProbe(val url: String, val latencyMs: Long, val error: String? = null) {
    val ok: Boolean                        // latencyMs != FAILED_LATENCY
    companion object { const val FAILED_LATENCY = Long.MAX_VALUE }
}

object MirrorProbeService {
    suspend fun probe(urls: List<String>): List<MirrorProbe>   // 已按延迟升序；全失败返回空列表
    suspend fun rank(candidates: List<String>): List<String>  // 全失败原样返回 candidates
}
```

- 超时：单镜像 `PER_MIRROR_TIMEOUT_MS = 4000`，整体 `TOTAL_TIMEOUT_MS = 6000`；探测用**独立** OkHttpClient（短超时、`retryOnConnectionFailure(false)`），与下载客户端隔离。
- `probe` 并发 `async` 探测全部去重候选；整体超时走 `withTimeoutOrNull` 返回 `null` 时**直接返回空列表**（不再对未完成的重发，避免用户反复等待）。
- 探测方式：先 `HEAD`，响应码不在 `200..399` 或抛异常时退化为 `GET` + `Range: bytes=0-0` + `Accept-Encoding: identity`；`200`（整文件）与 `206`（部分内容）都算可达。部分 CDN 不支持 HEAD，直接判失败会误伤。
- `rank`：候选 ≤ 1 时直接返回；否则按延迟排序，**未探测成功的按原顺序追加在后面保底**。
- ⚠️ **测速失败绝不能导致下载失败**（§8.20）。`probe` 返回空列表是合法状态，调用方必须回退清单顺序。
- ⚠️ 测速只是**排序建议，不是信任来源**：它不校验内容，完整性仍然只由 SHA256（清单非空时）保证。

---

## 7. `RootfsExtractor` — 解压

```kotlin
object RootfsExtractor {
    const val FORMAT_TAR_GZ = "tar.gz"
    const val FORMAT_TAR_XZ = "tar.xz"
    fun extract(archive: File, format: String, destDir: File,
                onEntry: ((processed: Long, name: String) -> Unit)? = null)
}
```

**四类条目处理**

| 条目 | 处理 |
| --- | --- |
| 目录 | `mkdirs()`；把 `mode & 0x1FF` 记入 `dirModes`，**解压期间保持默认可写** |
| 符号链接 | `Os.symlink(linkName, path)`，`linkName` 原文写入（可为相对/绝对/悬空） |
| 硬链接 | 收集进 `pendingLinks`，主循环结束后多轮重试 |
| 普通文件 | `FileOutputStream` 写内容，再 `Os.chmod(path, mode & 0x1FF)` |

**三道安全/正确性措施（缺一不可）**

1. **路径穿越防护**：条目名去前导 `/` 后拼到 `destCanonical`，再取 `canonicalFile`；必须「等于 dest」或「以 `dest + separator` 开头」，否则记 `skip traversal` 并 `continue`。
2. **不跟随链接的删除**：`deleteNoFollow` 用 `Os.lstat` 探测（`ENOENT` 直接返回）后 `Os.remove`，避免 `File.delete` 跟随符号链接误删目标。
3. **权限还原顺序**：目录 chmod 放在最后**逆序**执行（`dirModes.indices.reversed()`），因为解压期间需要写权限。

**硬链接收敛策略**：`while (progress && pendingLinks.isNotEmpty())` —— 一轮内只要有任意一条成功就再来一轮，全轮无进展则退出（放弃剩余，仅记 warn）。避免了「目标文件在归档中后出现」的失败。

**为什么不用 `java.nio.file`**：`Files` / `toPath()` 是 API 26+，`minSdk 24` 下会 `NoSuchMethodError`。`android.system.Os` 自 API 21 可用，且提供 `lstat`/`remove`/`symlink`/`link`/`chmod` 这些 AT 语义正确的原语。

---

## 8. `ProotSession` — PRoot 进程

```kotlin
class ProotSession(
    prootBin: File, loader: File, rootfs: File, shell: String,
    prootTmpDir: File, extraLibDir: File?,
    versionId: String, versionName: String,
    limits: ResourceLimits = ResourceLimits.UNLIMITED,   // ← 新增
    listener: Listener? = null
) {
    interface Listener { fun onOutput(text: String); fun onExit(code: Int, reason: ExitReason) }
    enum class ExitReason { NORMAL, DESTROYED, START_FAILED }

    fun start(); fun sendCommand(cmd: String); fun sendRaw(data: String); fun destroy()
    val isRunning: Boolean
    fun snapshot(): String; fun appendLocal(text: String); fun clearOutput()
    fun attach(l: Listener?); fun detach(); val truncatedChars: Int

    companion object { const val SNAPSHOT_TIMEOUT_MARK = "[ProotTerm] 读取会话输出超时，请稍后重试\n" }
}
```

**命令行**

```
<nativeLibraryDir>/libproot.so -r <rootfs> -0 -w /root \
  -b /dev -b /proc -b /sys -b /dev/urandom:/dev/random <defaultShell>
```

**`limits` → guest 命令（3 个独立 argv，不是 1 个字符串）**

```kotlin
val script = limits.toScript(shell)          // null = 无限制
val guestArgs = limits.toGuestCommand(shell) // 无限制 → [shell]；有限制 → [/bin/sh, "-c", script]
val command = buildList { /* proot 参数 */ ; addAll(guestArgs) }
```

- 无限制（或全为 0）时 `toGuestCommand` 返回 `listOf(shell)`，命令行与旧版**逐字节一致**。
- 有限制时追加的是**三个** argv：`/bin/sh`、`-c`、脚本文本（如 `ulimit -v 524288; exec "/bin/sh"`）。
- 传了限制时多记一行日志：`limits $versionId: <summary> -> sh -c '<script>'`。

> ⚠️ **绝不要把整段脚本当成一个 argv 传给 proot。** proot 不像 `env`/`nice` 那样替你调用 shell：它把最后一个参数当作**可执行文件路径**解析（proot 源码 `which()`：字符串含 `/` 时按显式路径 `realpath`，失败即报 `'%s' not found (root = …, $PATH=…)`），随后 `launch_process()` 直接 `execvp(tracee->exe, argv)`。形如 `ulimit -v 1024; exec "/bin/sh"` 的**单个字符串**会被当成文件名，后果是**凡是设置了资源限制的会话一律启动失败**。`toGuestCommand()` 返回的就是可以安全 `addAll` 的 argv 列表。

**环境变量**

| 变量 | 值 | 说明 |
| --- | --- | --- |
| `PROOT_TMP_DIR` | `<filesDir>/proot_tmp` | 必须绝对路径，且**固定内部**（§8.14） |
| `PROOT_LOADER` | `<nativeLibraryDir>/libproot-loader.so` | 必须绝对路径 |
| `LD_LIBRARY_PATH` | `<filesDir>/native`（前缀方式拼接） | 补 `libtalloc.so.2` / `libandroid-shmem.so` |
| `TERM` | `xterm-256color` | guest 侧 |
| `HOME` / `LANG` / `TMPDIR` | `/root` / `C.UTF-8` / `/tmp` | guest 侧 |
| `PATH` | `/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin` | guest 侧 |
| `PROOTTERM_VERSION` | `<versionId>` | 便于 guest 侧脚本识别当前系统 |

**生命周期**

- `start()` 前置校验四件套：`libproot.so` 是文件、loader 是文件、rootfs 是目录、`prootTmpDir` 可创建；缺任一抛 `IOException`（带中文可读消息）。`prootBin.setExecutable(true, false)` 兜底权限。
- `redirectErrorStream(true)` ⇒ stderr 并入 stdout，`readLoop` 单线程读取。
- `readLoop` 在名为 `proot-stdout` 的守护线程跑；**不再把每块输出写 `AppLogger`**（会刷爆 1MB 环形缓冲、挤掉启动失败信息），只 `mainHandler.post` 回调主线程。
- `isRunning`：API 26+ 用 `Process.isAlive`；API 24/25 用 `exitValue()` 抛 `IllegalThreadStateException` 反推。
- `destroy()`：先关 writer、`process = null`、`p.destroy()`，再由 `proot-reaper` 线程在 API 26+ 等 2s 后 `destroyForcibly()`；API 24/25 只有阻塞 `waitFor()`。
- `sendCommand` / `sendRaw` 都做 `synchronized(this)`，与 `readLoop` 里关闭 writer 的临界区互斥。
- 退出回调由 `AtomicBoolean exitNotified` 保证**只触发一次**（`DESTROYED` 与进程自然退出可能竞争）。

**输出缓冲线程约定**

内部 `StringBuilder` **只在主线程读写**。`readLoop` 后台线程通过 `mainHandler.post` 追加。`snapshot()` 在非主线程调用时会 post 回主线程并用 `CountDownLatch` 等最多 2 秒（`SNAPSHOT_TIMEOUT_MS`）；**超时返回 `SNAPSHOT_TIMEOUT_MARK` 而不是空串**——返回空串会让 UI 把一个正在运行的会话显示成「无输出」。`MAX_OUTPUT_CHARS = 200_000`，超出丢弃最旧一半并累加 `truncatedChars`。

`detach()` **不清空输出缓冲**（Activity 重建要靠快照恢复历史）；用户主动关闭终端时由调用方显式 `clearOutput()`。

**坑**

- 非 PTY：guest 不会输出提示符，输入也不回显 —— UI 用本地 `$ <cmd>` 回显补偿，文档提示用户敲 `sh -i`。
- `destroy()` 后 `writer` 为 null，后续 `sendCommand` 会抛 `IOException("Shell 未运行")`，`SessionManager` 转成状态栏文案。
- **外部存储位置可能直接起不来**：`/Android/data` 在 Android 11+ 由 FUSE 提供、通常不可执行。`explainStartFailure()` 会翻译成可操作提示。
- ⚠️ **`appendLocal()` 与 `SNAPSHOT_TIMEOUT_MARK` 目前没有任何调用方**（`MainActivity` 自己维护 `outputRaw` 本地回显）。保留它们是 API 完整性的一部分，不要因为「看着没人用」就删——它们是「本地提示必须与会话输出同一缓冲」这条约定的唯一出口。

---

## 9. `ResourceLimits` — 实例资源限制（ulimit 语义）

```kotlin
data class ResourceLimits(
    val memoryMb: Int = 0,        // ulimit -v（虚拟内存/地址空间，MB）
    val maxProcesses: Int = 0,    // ulimit -u
    val maxOpenFiles: Int = 0,    // ulimit -n
    val cpuSeconds: Int = 0,      // ulimit -t（CPU 时间，秒）
    val stackMb: Int = 0          // ulimit -s（MB）
) {
    val isUnlimited: Boolean                       // 全部 <= 0
    fun toGuestCommand(shellPath: String): List<String>  // 追加到 proot 命令行的 argv
    fun toScript(shellPath: String): String?       // 脚本文本；无限制返回 null
    fun summary(): String                          // 「不限制」/「内存 512MB · 进程 128」
    fun toJson(): JSONObject
    companion object {
        const val KEY_MEMORY = "memory_mb"; const val KEY_PROCESSES = "max_processes"
        const val KEY_OPEN_FILES = "max_open_files"; const val KEY_CPU_SECONDS = "cpu_seconds"
        const val KEY_STACK = "stack_mb"
        const val SH_PATH = "/bin/sh"              // 包装脚本用的解释器
        val UNLIMITED = ResourceLimits()
        val PRESETS: List<Pair<String, ResourceLimits>>   // 4 档中文预设
        fun fromJson(o: JSONObject?): ResourceLimits      // null → UNLIMITED
    }
}
```

**为什么是 ulimit 而不是 cgroup**：免 root 的 Android 应用无法写入 cgroup v2 的 `cpu.max` / `memory.max`（属 root 与 system_server 管辖），「硬限额」拿不到。`ulimit` 是进程自身就能设置的资源上限。

**取值约定：0 = 不限制**，此时**不生成**对应的 `ulimit` 语句。`isUnlimited` 把所有 `<= 0` 都当作不限制（配置被手工改成负数也走这条）。

**MB → ulimit 的单位换算（最容易写错的一处）**

| 字段 | ulimit 语句 | 单位处理 |
| --- | --- | --- |
| `memoryMb` | `ulimit -v ${memoryMb * 1024}` | ⚠️ `-v` 的单位是 **KB**，必须 ×1024，否则 512MB 会被当成 512KB |
| `stackMb` | `ulimit -s ${stackMb * 1024}` | ⚠️ 同理，`-s` 也是 KB |
| `maxProcesses` | `ulimit -u $maxProcesses` | 原值 |
| `maxOpenFiles` | `ulimit -n $maxOpenFiles` | 原值 |
| `cpuSeconds` | `ulimit -t $cpuSeconds` | 原值 |

语句顺序固定为 `-v`、`-s`、`-u`、`-n`、`-t`，用 `"; "` 连接，末尾追加 `; exec "$shellPath"`。这是 `toScript()` 的正文。

**argv 契约：`toGuestCommand` 与 `toScript` 的分工**

| 方法 | 返回 | 用途 |
| --- | --- | --- |
| `toGuestCommand(shellPath)` | `List<String>` —— 无限制时 `[shellPath]`，有限制时 `["/bin/sh", "-c", script]` | **唯一**可以拼进 `ProcessBuilder` 命令行的形式 |
| `toScript(shellPath)` | 脚本文本，无限制时 `null` | 生成脚本正文；也用于日志 |

> ⚠️ **`toScript` 的返回值绝不可以作为单个 argv 传给 proot。** proot 不是 `env`/`nice`：它把最后一个参数当**可执行文件路径**去解析（`which()`：含 `/` 时按显式路径 `realpath`，失败即 `'%s' not found (root = …, $PATH=…)`），然后直接 `execvp`。把 `ulimit -v 1024; exec "/bin/sh"` 当成一个字符串，会被当成文件名，**凡是设置了资源限制的会话全部启动失败**——这正是历史上真实踩过的坑。`ProotSession.start()` 现在是 `addAll(guestArgs)` 追加 3 个独立 argv。
>
> `SH_PATH = "/bin/sh"` 而不是 bash：guest 内各发行版都保证有 `/bin/sh`（Debian/Ubuntu 是 dash，Alpine 是 busybox ash），**Alpine 默认没有 bash**。

**为什么 `exec` 不可省略（§8.18）**

不用 `exec` 时进程树是 `proot → sh -c → sh(真正的 shell)`：`ProotSession.destroy()` 只能杀掉中间那层 `sh -c`，**真正的 shell 会变成孤儿继续持有 rootfs**，用户看到的是「结束了但目录还被占用」。用 `exec` 让真正的 shell **替换**包装进程，进程树保持两层，`destroy()` 才有效。

**其他坑**

- `memoryMb` 限制的是**地址空间**而不是 RSS；动态链接器在地址空间被压得过低时会直接失败，有效下限约 64MB。UI 不宜提供过小的自定义值。
- `cpuSeconds` 是**累计 CPU 时间**，不是「CPU 核数」也不是「占用百分比」，超限后内核直接 SIGKILL。**UI 文案必须如实说明**，不得描述成核数限制（§8.18）。
- `PRESETS`（4 档）：不限制 / 轻量（512MB、128 进程）/ 标准（1GB、256 进程、1024 FD）/ 编译用（2GB、512 进程、4096 FD）。预设只是**回填表单**，用户仍可继续微调。
- 资源限制**只影响新建会话**：改完必须重启该实例的会话才生效，UI 必须提示。

---

## 10. `InstanceConfig` / `InstanceStore` — 实例级配置

```kotlin
data class InstanceConfig(
    val id: String,                              // 实例 id = rootfs 目录名
    val displayName: String = "",                // 空串 = 跟随清单名
    val limits: ResourceLimits = ResourceLimits.UNLIMITED,
    val mirrorOverride: String = "",             // 空 = 只用清单地址
    val preferAutoMirror: Boolean = true,
    val downloadThreads: Int = DEFAULT_THREADS,  // 4
    val aptMirror: String? = null                // null = 未换源
) {
    val safeThreads: Int                         // coerceIn(1, MultiPartDownloader.MAX_THREADS)
    fun toJson(): JSONObject
    companion object {
        const val DEFAULT_THREADS = 4
        // KEY_ID / KEY_NAME / KEY_LIMITS / KEY_MIRROR / KEY_AUTO_MIRROR
        // / KEY_THREADS / KEY_APT_MIRROR
        fun fromJson(o: JSONObject): InstanceConfig
    }
}

class InstanceStore private constructor(context: Context) {
    fun get(id: String): InstanceConfig                       // 永不返回 null
    fun put(config: InstanceConfig)
    fun updateLimits(id: String, limits: ResourceLimits)
    fun updateName(id: String, name: String)
    fun updateMirror(id: String, override: String, autoMirror: Boolean,
                     threads: Int = get(id).downloadThreads)
    fun updateAptMirror(id: String, mirror: String?)          // null = 恢复原始源
    fun remove(id: String)
    fun pruneMissing(aliveIds: Set<String>): Int
    fun normalizeMirror(input: String): String?
    companion object {
        const val FILE_NAME = "instances.json"
        fun of(context: Context): InstanceStore                // 进程内共享实例
    }
}
```

**⚠️ 必须用 `InstanceStore.of(context)`，不要 `InstanceStore(context)`**

构造函数是 `private`，唯一入口是 `of(context)`，它返回**进程内共享的同一个实例**（双重检查 + `@Volatile shared`，用 Application Context 持有，不泄漏 Activity）。

原因：`MainActivity` 与 `ProotService` 都要读实例配置。若各自 `new` 一个，就会有**两份缓存**——UI 保存了新的资源限制，服务读到的可能仍是旧值，表现为「保存了但没生效」。`of()` 让两处共享同一份缓存，从根上消除这个漂移。

- `put` / `persistLocked` 在 `synchronized(lock)` 内执行；`map()` 用**双重检查**加载缓存（`cache` 本身也是 `@Volatile`）。因为 `ProotService` 会在 IO 线程读配置，这里不能像早期版本那样假设「所有访问都在主线程」。
- `persistLocked()` 先在锁内做快照再序列化，避免序列化期间与别的 `put` 冲突。调用方必须已持有 `lock`。

**契约要点**

- **`get()` 永不返回 null**：没有记录时返回 `InstanceConfig(id = id)`（全默认值），调用方无需判空。
- `safeThreads` 在**读取时**夹取到 `1..8`，不信任存储值——配置文件可能被手工改过，或被旧版本写成 0/负数，直接拿去分块会除零或产生空块。`updateMirror` 写入时也夹一次。
- `persist()` 走「临时文件 `instances.json.tmp` + 先删目标再 rename」的原子替换，避免进程被杀时留下半个 JSON 导致**全部**实例配置丢失。写入前按 `id` 排序，文件内容稳定（便于 diff 与人工查看）。
- 文件位置：**内部 `filesDir/instances.json`**，不跟随存储位置选择。配置很小、必须最可靠，且切换存储位置后仍要能读到（§8.14 的同一理由）。
- 读取失败（JSON 损坏）时**回退为空配置并记日志**，不让配置问题拖垮整个应用。
- 内存缓存 `cache` 懒加载，且**所有访问都在主线程**，因此没有加锁。
- `pruneMissing(aliveIds)` 清理已不存在的实例配置，返回清理数量；卸载系统时应调 `remove(id)`（`MainActivity.confirmUninstall` 已这么做）。

**`normalizeMirror` 的三态返回（易错）**

| 输入 | 返回 | 含义 |
| --- | --- | --- |
| `""` / 全空白 | `""` | 合法的「不自定义」 |
| `http://…` / `https://…` | 去尾部 `/` 的地址 | 合法 |
| 其他（`file://`、`ftp://`、裸域名…） | `null` | 非法，**调用方必须提示且不保存** |

> ⚠️ 只允许 http/https（§8.20）：用户可能粘贴 `file:///...`，让 OkHttp 去加载本地文件不仅无意义，还会绕过「只读写应用私有目录」的边界。`MainActivity.saveInstanceSettings` 收到 `null` 时**整体放弃保存**（不做部分保存）。
>
> ⚠️ 去掉尾部 `/` 是为了避免与清单地址拼接时出现 `//`。

**`aptMirror` 的 null 哨兵**：`toJson` 用 `JSONObject.NULL` 而不是移除字段。`optString` 对「缺失」与「null」都给 `""`，而这里需要区分「没换过源」与「换过但记录为空」；`fromJson` 用 `isNull(KEY_APT_MIRROR)` 判断并 `ifBlank { null }`。

**坑**

- `InstanceStore` **不是单例**：`MainActivity` 与 `ProotService` 各持一个实例，各自有独立内存缓存。同一进程内两处先后写入时，后写者可能用陈旧缓存覆盖前者的改动。当前写入点分散在 UI（保存设置 / 换源 / 卸载）与服务（只读 `limits`），暂未冲突；**若将来要在服务里写配置，必须先解决这个缓存一致性问题**。
- `InstanceStore(context)` 内部用 `context.filesDir`，传 Activity 或 Service 都可以，但不要长期持有 Activity。

---

## 11. `AptSourceSwitcher` — APT / apk 换源

```kotlin
data class AptMirror(val id: String, val label: String,
                     val debianBase: String, val alpineBase: String) {
    companion object {
        val ALL: List<AptMirror>                        // tuna / ustc / aliyun / huawei
        fun ubuntuPortsBase(mirror: AptMirror): String  // Ubuntu arm64 专用基址
        fun fromId(id: String?): AptMirror?             // 未知 id → null
    }
}

sealed class AptSwitchResult {
    data class Success(val detail: String) : AptSwitchResult()
    data class Failure(val reason: String) : AptSwitchResult()
}

object AptSourceSwitcher {
    const val BACKUP_SUFFIX = ".prootterm.bak"
    fun switchTo(rootfs: File, mirror: AptMirror, distroId: String): AptSwitchResult
    fun restoreOriginal(rootfs: File): AptSwitchResult
    fun hasBackup(rootfs: File): Boolean
}
```

**三套规则**

| 发行版 | 文件 | 格式 |
| --- | --- | --- |
| Debian 12+ / Ubuntu 24.04+ | `etc/apt/sources.list.d/*.sources` | **deb822**（多行 `URIs:` / `Suites:` / `Components:`） |
| 旧版 Debian / Ubuntu | `etc/apt/sources.list` | 单行 `deb <url> <suite> <components>` |
| Alpine | `etc/apk/repositories` | 一行一个仓库 URL |

策略：**优先改 deb822**，目录里没有任何 `.sources` 才改旧式单行。两者都改看似更彻底，但若系统只用其中一种，改另一个等于留下一个不生效的垃圾文件。

**arm64 路径陷阱（`apt update` 报 404 的头号原因）**

| 发行版 | x86 路径 | **arm64 实际路径** |
| --- | --- | --- |
| Debian | `.../debian` | **`.../debian-ports`** |
| Ubuntu | `.../ubuntu` | **`.../ubuntu-ports`** |

`AptMirror.debianBase` 已含 `debian-ports`；Ubuntu 由 `ubuntuPortsBase(mirror)` 推导。`switchTo` 用 `distroId.contains("ubuntu")` 决定用哪个基址，组件集也随之为 `main restricted universe multiverse` 或 `main contrib non-free`。

**发行版代号必须从 rootfs 读**：`readOsRelease` 手工解析 `etc/os-release` 取 `VERSION_CODENAME`（如 `bookworm` / `noble`），空则回落 `stable`。**禁止硬编码猜测**（§8.21）——应用发布节奏与发行版升级节奏无关。
⚠️ `os-release` 是 `KEY="value"` 格式，**不能**用 `java.util.Properties` 读（引号会被当成值的一部分），必须手工解析并 `trim('"', '\'')`。
⚠️ `switchTo` 里 `codename` 的两行 `.ifBlank { … }` 是**同义重复**（内层与外层都取 `VERSION_CODENAME`），实际效果等于直接取该键。是可读性冗余，不是 fallback 到别的键。

**Alpine 的特殊点**：`/etc/apk/repositories` 是纯 URL 列表。解析方式是从原行里找 `/alpine/` 子串，取出其后的 `branch/repo`（如 `v3.20/main`）再拼到 `mirror.alpineBase` 上——**不写死版本号**，这样 Alpine 升级后不会失效。空行与 `#` 注释原样保留；不含 `/alpine/` 的行（自定义 CDN）保留原样并记 warn；一行都没改写成功则返回 `Failure`。

**幂等与可逆（三条铁律）**

1. **备份只在不存在时创建**（`backupOnce`：`backup.exists()` 就直接返回并记日志）。这是「可恢复」的前提：第二次换源若用已改过的内容覆盖备份，原始源**永久丢失**。
2. **原子写**：`writeAtomic` = 写 `<name>.tmp` → 删目标 → `renameTo`。中断最多留下一个 `.tmp`，不会留下半个 `sources.list` 让 apt 彻底不可用。备份与恢复都走同一个 `copyAtomic`。
3. **找不到备份就如实报错**：`restoreOriginal` 扫 `sources.list`、`apk/repositories` 与 deb822 目录下的所有 `*.prootterm.bak`，一个都没有时返回 `Failure("没有找到任何备份…")`，**不假装成功**。

**其他要点**

- 改的是**宿主侧**的 rootfs 文件路径（`<rootfs>/etc/...`），不需要启动 guest：换源只是改文本，起 shell 去做反而更慢也更容易出错。
- `rewriteDeb822` 只替换 `URIs:` 行、确保 `Suites:` / `Components:` 正确，**保留注释与其余字段**（如 `Signed-By`）——签名密钥路径改错会让 apt 直接拒绝整个源。整份文件没有 `URIs:` 行时**原样返回**，不补全出一个无效源。
- `rewriteLegacyList` 只改 `deb `/`deb-src ` 开头的行；注释掉的行保持原样（用户可能有意保留）。
- `restoreOriginal` 恢复成功后会**删除备份文件**，因此「已恢复」状态 `hasBackup` 会回到 false。
- ⚠️ 实例正在运行时换源会提示「重启会话后生效」（`apt_running_warning`）：文件系统是共享的，改动立刻可见，但 guest 内已运行的 apt 可能缓存了旧源列表。
- ⚠️ 失败时**不写 `InstanceConfig.aptMirror`**，否则界面会显示「已换源」而文件其实没改（`MainActivity.applyAptMirror` 严格按 `Success/Failure` 分支处理）。

---

## 12. 会话与后台

这一组是本项目最重要的结构约束：**shell 的生命周期不由 Activity 决定**。
`MainActivity` 只是观察者（`onStart` attach / `onStop` detach），**不拥有任何会话**；
`ProotService.onDestroy` 同样**禁止**结束会话（§8.13）。

### 12.1 `SessionManager` — 多实例会话注册表

```kotlin
object SessionManager {
    interface Observer {
        fun onSessionState(id: String, state: State, versionName: String?)
        fun onOutput(id: String, text: String)
        fun onExit(id: String, code: Int, reason: ProotSession.ExitReason)
    }
    enum class State { IDLE, STARTING, RUNNING, STOPPING }

    fun init(context: Context)                        // App.onCreate 注入 Application Context
    fun stateOf(id: String): State                    // 不存在的实例视为 IDLE
    fun isRunning(id: String): Boolean
    fun hasAnyRunning(): Boolean
    fun runningIds(): List<String>                    // 已排序
    fun lastError(id: String): String?
    fun versionNameOf(id: String): String?
    fun snapshot(id: String): String
    fun truncatedChars(id: String): Int
    fun clearOutput(id: String)
    fun attach(o: Observer?); fun detach(o: Observer)
    fun prepare(id: String, versionName: String)
    fun startBlocking(context: Context, manager: RootfsManager, distro: DistroInfo,
                      limits: ResourceLimits = ResourceLimits.UNLIMITED): String?
    fun sendCommand(id: String, command: String)      // 无会话抛 IOException
    fun sendRaw(id: String, data: String)             // 无会话抛 IOException
    fun stop(id: String): Boolean                     // true = 确实有会话被结束
    fun stopAll(): Int                                // 返回结束的实例数
    fun forget(id: String)
    fun notifyStartFailed(id: String, message: String)
    fun notificationTitle(): String; fun notificationText(): String
    fun openTerminalIntent(context: Context): Intent

    const val ABORTED = "\u0000aborted\u0000"
}
```

内部结构（`SessionSlot` 是 `private`，仅供参考）：

```kotlin
private val slots = ConcurrentHashMap<String, SessionSlot>()   // key = 实例 id

private class SessionSlot(val id: String) {
    val epoch = AtomicInteger(0)          // ← 每个实例自己的代次
    @Volatile var state: State = State.IDLE
    @Volatile var session: ProotSession? = null
    @Volatile var versionName: String? = null
    @Volatile var lastError: String? = null
}
```

**⚠️ 刻意没有「当前会话」访问器**

`SessionManager` **不提供**无参的 `snapshot()` / `sendCommand()` / `sessionOrNull()` 之类的全局访问器，一个都不要加。「当前会话」在多实例下是**终端焦点**概念，属于 UI 层（`MainActivity.focusedId`）；放进 `SessionManager` 就会诱导调用方写出串实例的代码（§8.17 明文禁止）。所有查询/操作都必须带实例 id。

**`startBlocking` 的三态返回值（最容易用错的地方）**

| 返回 | 含义 | 调用方该做什么 |
| --- | --- | --- |
| `null` | 启动成功，会话已就绪 | 挂 `watchSessionEnd()`、写 `lastVersionId` |
| `ABORTED` | 启动期间被用户取消，**什么都没起来** | `notifyStartFailed` + `releaseIfIdle()`，**不能**按成功处理 |
| 其他字符串 | 具体失败原因 | 更新通知文案 + `releaseIfIdle()` |

> ⚠️ `ABORTED` 不能复用 `null`：`ProotService` 把 `null` 当成功，若取消也返回 `null`，会留下一个永远等不到会话结束的前台服务（通知常驻不消）。
>
> ⚠️ 失败分支**不要**再调 `notifyStartFailed`：`startBlocking` 的 `catch` 已经 `setState(IDLE)` + 发过 `onExit(START_FAILED)` 了，再发一次终端里会出现两条一模一样的「启动失败」。`ABORTED` 分支才需要显式补通知（它什么都没发）。

**每槽独立 epoch（多实例的关键）**

- `prepare(id)` 与 `stop(id)` 都对自己的槽 `epoch.incrementAndGet()`。旧单例实现只有一个**全局** epoch，两个实例同时启动时会互相把对方判成「已被取消」而误杀；现在取消只影响被取消的那个实例。
- `startBlocking` 在 **`NativeDeps.ensure()` 之后**与 **fork 之后**各比对一次 epoch；不符就销毁刚起来的进程并返回 `ABORTED`。
- 用 `AtomicInteger` 而非 `@Volatile Int`，因为 `++` 是读-改-写，并发自增会丢更新而让校验失效。

**`prepare(id, versionName)` 必须先销毁旧会话**：`startBlocking` 会覆盖 `slot.session`，不先 `destroy()` 旧会话就会留下无人持有、也无法从 UI 结束的孤儿 proot 进程。**只销毁该实例自己的旧会话**，其他实例完全不受影响。

**其他行为约定**

- `slots` 用 `ConcurrentHashMap`：`startBlocking` 在 IO 线程 `computeIfAbsent` 建槽，UI 线程同时在读，普通 `HashMap` 并发读写会结构性损坏。
- `hasAnyRunning()` / `runningIds()` 的判据是 `slot.session?.isRunning == true`（**进程真的在跑**），与 `stateOf()` 的 `state` 字段是两套互补信号。
- `onSessionExit` 会把 `versionName` 清成 `null`——否则通知标题会继续显示一个已不存在的会话名。
- `stop(id)` **同步**把状态切回 `IDLE`（不等内核收尸）；`onExit` 回调由 `ProotSession.exitNotified` 保证只触发一次。
- `stopAll()` 只应在用户显式选择「全部结束」时调用。`onDestroy` 里调用它是被 §8.13 明令禁止的。
- `forget(id)` = `stop(id)` + 移除槽，卸载实例时调用，避免 `slots` 无限增长。
- `notifyStartFailed(id, message)` 用于**启动流程未走到 `prepare` 就失败**的早退路径（清单里没这个版本 / 尚未安装），那时状态机还在 IDLE，观察者收不到任何回调，UI 会永远卡在「正在启动…」。
- 通知文案需要 `Context`，`init(context)` 由 `App.onCreate` 注入一次（存 `applicationContext`，不会泄漏 Activity）；注入失败时退回英文常量兜底。

**观察者必须按 id 过滤**

`observer` 是单个可空字段（同一时刻只有一个 Activity 存活，无需多播）。⚠️ 忘了在回调里判断 `id == focusedId` 的后果不是崩溃而是**串台**：A 实例的执行结果出现在 B 的终端里，用户会以为命令跑错了地方。

**通知文案（`notificationTitle` / `notificationText`）**

多实例下通知是**一条汇总通知**，不是每个实例一条（前台服务本身也只能有一条常驻通知）：

- 标题：0 个运行中 → `ProotTerm`；1 个 → `ProotTerm：<显示名>`；多个 → `ProotTerm：N 个实例运行中`。
- 正文：有实例在跑 → 单个时用 `notif_running`，多个时列出全部 id（顿号连接）；否则按 `STOPPING` → `STARTING` → 空闲的优先级挑文案。

### 12.2 `ProotService` — 前台服务

```kotlin
class ProotService : Service() {
    companion object {
        const val ACTION_START, ACTION_STOP, ACTION_AUTO_START, ACTION_OPEN_TERMINAL: String
        const val EXTRA_VERSION_ID = "version_id"
        const val EXTRA_INSTANCE_ID = "instance_id"
        fun startIntent(context, versionId: String): Intent
        fun autoStartIntent(context, versionId: String): Intent
        fun stopIntent(context): Intent                    // 结束全部
        fun stopIntent(context, instanceId: String): Intent // 只结束一个
        fun launch(context: Context, intent: Intent): Boolean
    }
}
```

**多实例相关的三个实现点**

1. `startJobs: ConcurrentHashMap<String, Job>` —— 旧实现用单个 `startJob` 字段，并发启动两个实例时后一个会 `cancel()` 掉前一个尚未完成的启动，表现为「点第二个系统，第一个没起来」。
2. **`CoroutineStart.LAZY`**：`val job = scope.launch(start = LAZY) { … }` → `startJobs[id] = job` → `job.invokeOnCompletion { startJobs.remove(id) }` → `job.start()`。不能在协程体内部引用外层的 `job`（`scope.launch` 返回前协程可能已开始执行，Kotlin 的 definite-assignment 规则会拒绝编译，用 `var` 则会读到 `null`）。用 LAZY 让「拿到句柄 → 登记 → 启动」顺序确定，早退路径（清单里没这个版本 / 尚未安装）也已被登记，不会留下过期引用导致该实例再也无法重新启动。
3. `releaseIfIdle()` 与 `watchSessionEnd()` 的**判定条件都是 `hasAnyRunning()`**，不是「某个实例 IDLE」。单个实例启动失败/被取消 **不等于** 服务该结束——无条件 `stopSelfSafely()` 会把其他正在运行的实例连通知一起清掉。

**`ACTION_STOP` 的两种语义**

| Intent | 行为 |
| --- | --- |
| `ACTION_STOP` 带 `EXTRA_INSTANCE_ID` | 只 `SessionManager.stop(target)`；若 `hasAnyRunning()` 则只刷新通知，否则才 `stopSelfSafely()` |
| `ACTION_STOP` 不带（或空串） | `SessionManager.stopAll()`，然后 `stopSelfSafely()` |

通知栏的「结束」按钮**故意不带** `EXTRA_INSTANCE_ID`（结束全部），与按钮文案「结束」的语义一致。

**关键点**

- **会话的唯一权威结束点是 `ACTION_STOP`**。
- ✅ `onDestroy` **故意不调用** `SessionManager.stopAll()` / `stop(id)`：`onDestroy` 不等于「用户要结束会话」，系统内存紧张 / OEM 后台清理 / 前台服务被回收时都会销毁服务——那恰恰是前台服务要扛住的场景。在这杀会话会让保活与自启动双双失效（§8.13）。`onDestroy` 只做 `scope.cancel()` + `startJobs.clear()`。
- **必须先 `startForegroundCompat()` 再 fork**：Android 8+ 要求 `startForegroundService` 后 5 秒内 `startForeground`，而准备 rootfs 可能更慢，否则 ANR。
- 启动失败的**三条路径**都要 `notifyStartFailed(...)`：前两条（清单里没这个版本 / 尚未安装）发生在 `prepare()` 之前，那条路径下状态机还在 IDLE，观察者收不到任何回调，UI 会永远卡在「正在启动…」。第三条是 `ABORTED`。
- `startSession` 开头先查 `SessionManager.isRunning(id)`（**只查目标实例**），已在跑就直接返回不重启。
- 每个实例的资源限制从 `instanceStore.get(id).limits` 读，用 `withContext(Dispatchers.IO)` 包住（首次读文件），再传给 `startBlocking`。
- `watchSessionEnd()` 每 `WATCH_INTERVAL_MS = 1500ms` 轮询 `hasAnyRunning()`，全部结束后 `stopSelfSafely()`。
- 服务实例是**多实例共享的一个**：所有实例共用同一个前台服务、同一条通知。服务里还持有 `private val instanceStore by lazy { InstanceStore.of(this) }`——与 `MainActivity` 共享**同一个**实例，否则两处缓存漂移会导致「UI 保存了限制但服务读到旧值」（§10）。
- 通知渠道 `proot_session`，`NOTIF_ID = 1001`，`IMPORTANCE_LOW`，`setOngoing(true)`；Q+ 带 `FOREGROUND_SERVICE_TYPE_DATA_SYNC`；动作按钮图标传 `R.drawable.ic_terminal`（**不要传 `null`**：该参数是 `@DrawableRes Int`，传 `null` 会落到 `Icon` 重载，重载解析容易出错且 `Icon` 是 API 23+）。
- API 33+ 的 `POST_NOTIFICATIONS` 由 `MainActivity.requestNotificationPermissionIfNeeded()` 在启动会话时申请（`REQ_NOTIF = 9001`）；**拒绝也不阻断**：服务照常运行，只是看不到常驻通知。

### 12.3 `BootReceiver` — 开机自启动

```kotlin
class BootReceiver : BroadcastReceiver()   // BOOT_COMPLETED + MY_PACKAGE_REPLACED
```

- 只读 `AppPrefs`（`autoStart` + `autoStartId`），**不做任何存在性校验**，立刻 `ProotService.launch(...)`。
  广播有 10 秒预算，且开机瞬间外部存储可能尚未挂载，所有校验都交给服务做。
- 会构造一次 `RootfsManager`，只为在 `locationFallback == true`（外部存储未就绪）时记一条 warn 日志，不改变行为。
- 不处理 `LOCKED_BOOT_COMPLETED`（那时应用目录仍处于凭据加密锁定状态）。
- 用户「强制停止」应用后系统不再投递 `BOOT_COMPLETED`，必须重新打开一次应用——系统限制，无法绕过。

---

## 13. 「我的系统」列表

```kotlin
data class MyApp(
    val id: String, val name: String, val shell: String, val dir: File,
    val sizeBytes: Long, val fromManifest: Boolean,
    val manifestLoaded: Boolean = false
) { var distro: DistroInfo? }

object InstalledScanner {
    fun scan(rootfsRoot: File, shellFallbacks: List<String>): List<MyApp>
}
```

- 判定标准：`<root>/<id>/.installed` 存在（由 `finalizeInstall` 在原子切换成功后写入）。
- 扫描只列 `rootfs/` 根下**一层**，并过滤掉 `*.old` 备份目录，几毫秒完成；结果按 id 排序保证顺序稳定。
- shell 优先级：`recordedShell(id)`（`.shell` 文件）> 清单 `defaultShell` > 探测 `/bin/bash`、`/bin/sh`、`/usr/bin/bash`、`/usr/bin/sh` > `/bin/sh` 兜底。
- `MainActivity.refreshMyApps()` 负责把扫描结果与清单对齐（名称/shell/`fromManifest`/`manifestLoaded`），并**异步补齐目录占用**（`dirSize` 走 `Os.lstat` 判断符号链接，不跟随——rootfs 里有大量指向目录的链接，跟随会死循环或把宿主机目录算进来）。

### `MyAppAdapter`

```kotlin
class MyAppAdapter(
    onLaunch: (MyApp) -> Unit, onOpenTerminal: (MyApp) -> Unit,
    onUninstall: (MyApp) -> Unit, onShowInfo: (MyApp) -> Unit
) {
    fun submit(newItems: List<MyApp>)
    fun setRunningIds(ids: Set<String>)     // ← 集合，不是单个 id
}
```

> ⚠️ **运行状态是 `Set<String>` 而不是 `String?`**。多实例可以同时运行，`setRunningIds` 每次状态回调都会被调用；判据是 `app.id in runningIds`，按钮文案随之在「启动 / 打开终端」之间切换。
> `setRunningIds` 内部用 `symmetricDifference` 只 `notifyItemChanged` 变化过的那几项，避免整表重绘让列表滚动位置跳动；`refreshMyApps()` 里每次 `submit()` 之后都必须重新调一次 `setRunningIds`（`submit` 不会自动带上运行状态）。

> ⚠️ **`fromManifest == false` 有两种含义，必须用 `manifestLoaded` 区分**：
> - `manifestLoaded == true` 且 `fromManifest == false` ⇒ 清单读到了但没这个版本 ⇒ 真的是用户自己放进去的「自定义 rootfs」。
> - `manifestLoaded == false` ⇒ 清单根本没读到 ⇒ **不能**断言它是自定义的，文案应说「版本未知」。
>
> 混用这两者会把应用自己装的系统误报成用户手工放入的目录。

---

## 14. `NativeDeps`

```kotlin
object NativeDeps { fun ensure(context: Context): File }   // 返回 filesDir/native
```

- 从 `assets/native/arm64-v8a/` 释放 `libtalloc.so.2`、`libandroid-shmem.so` 到 `filesDir/native`。
- **幂等**：目标文件已存在且 `length > 0` 就跳过，不重复写。
- 失败时删除半成品再抛 `IOException`；成功后 `setReadable(true,false)` + `setExecutable(true,false)`。
- 为什么放 assets 而非 jniLibs：`libtalloc.so.2` **文件名不以 `.so` 结尾**，Android 打包器会跳过它，不会解压到 `nativeLibraryDir`；而 proot 的 `NEEDED` 就是这个名字，不能重命名。故走 assets 手动释放 + `LD_LIBRARY_PATH` 注入。
- `SessionManager.startBlocking` 调用它，但**吞掉异常只记 warn**（`nativeDir = null`）：运行库释放失败不应该直接判启动失败，让 proot 自己去报真正的缺失错误。

---

## 15. `ManifestLoader`

```kotlin
object ManifestLoader {
    const val ASSET_NAME = "rootfs_manifest.json"
    const val SUPPORTED_VERSION = 2
    fun load(context: Context): List<DistroFamily>
    fun parse(json: String): List<DistroFamily>
}
```

- 用 Android 内置 `org.json`（**不引入 kotlinx-serialization**，减依赖体积）。
- 必填字段用 `getString`/`getJSONArray`（缺失抛 `JSONException`），可选字段用 `optString`/`optLong`/`optJSONArray`（`icon` 缺省为家族 id，`sha256` 缺省空串，`size` 缺省 0）。
- **结构校验**：顶层 `version` 必须等于 `SUPPORTED_VERSION`，否则抛 `IllegalArgumentException`（`optInt("version", -1)`，缺失同样报错）。每个版本的 `format` 必须属于 `RootfsExtractor` 支持的集合；清单为空也报错。这样清单格式演进时是「明确失败」而不是静默解析出半份数据。
- `parse` 与 `load` 分离，便于单元测试直接喂 JSON 字符串。
- 解析失败由 `MainActivity.onCreate` catch：记日志 + Toast + 得到空列表（界面空但**不崩**）。
- `ProotService.startSession` 也调 `load()`，失败时 `distro == null` 走 `notifyStartFailed` 早退路径。

---

## 16. UI 层

### `DistroAdapter`

三张家族卡片。`bind` 时图标取 `family.name` 首字母大写；副标题用 `R.string.family_versions_fmt` 显示「N 个版本可下载」；卡片整体与按钮都触发 `onClick(family)` → `MainActivity.openVersions`。

### `VersionAdapter` — 状态机

```kotlin
sealed class State {
    data object Idle : State()
    data class Downloading(val percent: Int) : State()
    data object Cancelling : State()
    data object Installed : State()
    data class InstalledElsewhere(val locationLabel: String) : State()
}
fun setState(id: String, state: State, payload: String? = null)
fun stateOf(id: String): State
companion object { const val PAYLOAD_PROGRESS = "payload_progress" }
```

| 状态 | UI 表现 |
| --- | --- |
| `Idle` | 显示「下载」按钮，隐藏进度组、取消与卸载按钮 |
| `Downloading(pct)` | 隐藏下载按钮，显示 `ProgressBar` + `pct%` + 「取消」按钮 |
| `Cancelling` | 同上，但取消按钮置灰并显示「取消中…」，百分比文案改为「取消中」 |
| `Installed` | 显示「启动」按钮 + 「卸载」按钮 |
| `InstalledElsewhere(label)` | 显示「重新下载」按钮（当前位置没有 rootfs，启动会立刻报「尚未安装」） |

- 构造参数：`(items, onDownload, onStart, onUninstall, onCancelInstall)`，五个回调全部必填。
- 状态存在 `HashMap<String, State>`，键是 `DistroInfo.id`。注意 `setState` 会 `notifyItemChanged`，但 `states` 在 `index < 0` 时**已经写入**了 —— 即对不在当前列表的 id 设状态也会被记住。
- **局部刷新**：只有当 `payload == PAYLOAD_PROGRESS` 且前后都是 `Downloading` 且**百分比真的变了**时才走 `notifyItemChanged(index, payload)`，`onBindViewHolder(holder, position, payloads)` 重载里只更新进度条与百分比文案。这避免了每 200ms 整行重绘的闪烁。
- 按钮语义由**当前状态**决定：`Installed` → `onStart`；否则 → `onDownload`。取消按钮只在 `Downloading` 时触发 `onCancelInstall`（`Cancelling` 态点击无效，防重复取消）。
- 水平 `ProgressBar` 没有不定态动画，`Cancelling` 态刻意保留上一次百分比，只把文案改成「取消中」。
- `humanSize` 用 1000 进制（GB/MB/KB），保留一位小数。

### `MainActivity` — 五屏编排

```
Screen { MY_APPS, TERMINAL, DISTROS, VERSIONS, SETTINGS }   // 默认 MY_APPS
```

用 `visibility` 切换五个 `include` 出来的 View，**不是** Fragment / 多 Activity。`nav_logs` **不占 Screen**，直接 `startActivity(LogActivity)`。

**持有状态**

| 字段 | 用途 |
| --- | --- |
| `focusedId: String?` | 终端页当前显示的实例 id，**只是视图焦点，不是所有权**；切焦点不启动/结束任何进程 |
| `installJob: Job?` / `activeInstallId: String?` | 保证「同时仅一个安装任务」 |
| `lastDownloadPct: Int` | 切屏/重回版本页时恢复进度显示 |
| `downloader` / `multiDownloader` | `MultiPartDownloader(downloader)`，两者共享同一个 `RootfsDownloader` |
| `instanceStore: InstanceStore` | 实例级配置（`onCreate` 里 `InstanceStore.of(this)`，§10） |
| `outputRaw: StringBuilder` | 终端原始输出缓冲 |
| `searchQuery: String` | 搜索过滤词 |
| `modifierArmed: Modifier` | CTRL / ALT 组合键待命态（`enum Modifier { NONE, CTRL, ALT }`，两者互斥） |

**`install(d)` 的完整流程**：并发保护 → `checkSpaceFor` → `cleanupTemp` → 置 `Downloading(0)` → `scope.launch` 中依次：
`instanceStore.get(d.id)` 取配置 → 组装候选（自定义源 + `allUrls`，`distinct()`）→ `preferAutoMirror && size > 1` 时 `MirrorProbeService.rank()` → `multiDownloader.download(..., threads = cfg.safeThreads)` → SHA256（清单非空时）→ `extract` → `finalizeInstall(id, temp, defaultShell)` → `part.delete()` → 置 `Installed`。
异常分支：`CancellationException` 用 `cleanupChunksFor(manager, id)` 清分块、置 `Idle` 并**重抛**（协程语义要求）；其他 `Exception` 记日志 + `cleanupChunksFor` + `cleanupTemp` + 置 `Idle` + 状态栏文案 + Toast，**不闪退**；`finally` 清 `activeInstallId`，并把残留的 `Cancelling` 态兜回 `Idle`（防止按钮永久卡在「取消中」）。

注意 SHA256 失败会**先删掉 `.part`**（`part.delete()`）—— 与「续传」策略相反，因为内容已确认损坏，续传无意义。

**`cancelInstall(d)`**：校验 `activeInstallId == d.id` → 置 `Cancelling` → `multiDownloader.cancel()`（置取消标记 + 结束活动 OkHttp `Call`）+ `installJob.cancel()`。两者缺一：只 cancel 协程时阻塞在 socket 读的 IO 不会及时中断；只 cancel Call 时协程仍会继续走到后续步骤。单线程 `.part` 保留可续传，分块残留由取消分支的 `cleanupChunksFor` 清掉。

**实例设置（`showInstanceSettings` / `saveInstanceSettings`）**

- `showMyAppInfo` 的「实例设置」按钮进入 `dialog_instance_settings.xml`：五项资源限制输入 + 预设下拉 + 自动测速开关 + 自定义镜像输入 + 「测速」按钮 + 下载线程数 + 换源/恢复源按钮。
- `fill(...)` 回填时 **0 显示为空串**而不是 `"0"`：占位文本本身就是「不限制」，显式填 0 会让用户以为必须写数字。
- `saveInstanceSettings` 读取表单，**任一字段非法就整体放弃**并 Toast `limits_invalid`（不做部分保存——半套限制比不限制更难排查）；`normalizeMirror` 返回 `null` 时 Toast `mirror_custom_invalid` 同样不保存。
- 线程数写入前 `coerceIn(1, MultiPartDownloader.MAX_THREADS)`。
- 「测速」按钮走 `buildMirrorCandidates(app, editMirror.text)` + `MirrorProbeService.probe()`，结果逐行显示 `mirror_probe_result_fmt`；空结果提示 `mirror_probe_failed`（此时下载仍会回退清单顺序）。
- ⚠️ 资源限制与镜像偏好**都只影响之后的启动/下载**（限制需重启该实例会话生效），保存后只 Toast，不重启会话。

**换源入口（`applyAptMirror` / `restoreAptMirror`）**

- 选镜像 → `AptSourceSwitcher.switchTo(manager.distroDir(app.id), mirror, app.id)`（`Dispatchers.IO`）；`Success` 才 `instanceStore.updateAptMirror(id, mirror.id)`，`Failure` 只提示不写配置。
- 实例正在运行时先 Toast `apt_running_warning`（不是阻断，只是提醒重启会话）。
- 恢复走 `restoreOriginal`，`Success` 时 `updateAptMirror(id, null)`。

**终端交互**

- 命令发送：`sendCommand()` 取 `focusedId`，无焦点或不在跑就提示并跳回「我的系统」；否则 `SessionManager.sendCommand(fid, cmd)` + 本地回显 `$ <cmd>`。`sendRawKey()` 同理走 `SessionManager.sendRaw(fid, data)`。
- `appendOutput` → 超 `MAX_OUTPUT_CHARS = 200_000` 时 `delete(0, length - MAX/2)`：一次砍掉一半，摊薄裁剪开销。
- `renderOutput` → 有搜索词时按行 `filter { it.contains(q, ignoreCase = true) }` 后 join；`SessionManager.truncatedChars(fid) > 0` 且无搜索词时在顶部加 `output_truncated` 提示；空结果回落到 `R.string.terminal_empty`；每次渲染后 `fullScroll(FOCUS_DOWN)`。
- `restoreFromSession()`（`onStart` 调用）：焦点实例不在跑时挑 `runningIds().firstOrNull()` 接管终端，再用 `renderSessionOutput()` 从会话快照重放。**注意不要因为 snapshot 为空就提前返回**：刚 `clearOutput()` 过或刚 fork 还没吐字符时快照本来就是空的，此时应当照常重绘；会话在跑但无输出时**绝不能**塞占位提示（看起来像已断开）。
- 快捷键常量集中在 `companion object`：`KEY_ESC = "\u001b"`、`KEY_UP/DOWN/LEFT/RIGHT = "\u001b[A/B/C/D"`、`KEY_HOME = "\u001b[H"`、`KEY_END = "\u001b[F"`、`KEY_PGUP/PGDN = "\u001b[5~/6~"`、TAB = `"\t"`。**不要在源码里直接嵌入裸控制字符**（历史上 ESC/方向键就是裸 `0x1B` 字节，编辑器极易损坏）。
- CTRL / ALT 是**互斥的待命开关**（`setModifier`）：点击后按钮半透明 + 展开 `scroll_ctrl_letters` 字母行；点某个字母发对应控制序列后自动复位。
- 字母键由 `buildLetterRow()` 从 `item_ctrl_key.xml` 模板 inflate 26 次生成。**不要**改回 `resources.getIdentifier("btn_key_$ch", ...)`：布局里从来没有 `btn_key_a`…`btn_key_z`，那种写法恒返回 0，整段循环静默失效。
- 生成按钮必须走 inflate 而非 `MaterialButton(ctx)`：`MaterialButton(Context)` 构造函数硬编码读 `R.attr.materialButtonStyle`，传入 `ContextThemeWrapper(R.style.App_Key)` 并不会应用 `App.Key` 样式。
- `hardenImeForTerminal()` 规避安全键盘（§8.15，`importantForAutofill` 是 API 26+，必须分支）。

**`onBackPressed` 优先级**：抽屉打开 → 关抽屉；`VERSIONS` → 回 `DISTROS`；`TERMINAL` 且**焦点实例**在跑 → 弹「保持运行 / 关闭终端」；其他非 `MY_APPS` 屏 → 回 `MY_APPS`；否则交给父类（退出）。

**`showExitDialog`**：「保持运行」**不碰会话**，只回列表页（前台服务继续保活，通知常驻）；「关闭终端」先 `stopIntent(this, focusedId)` 只结束焦点实例，再补一次 `stopIntent(this)`（全部结束）兜底。

**`onDestroy` 清理链**：`installJob?.cancel()` + `downloader.cancel()` + `scope.cancel()`。
⚠️ **绝不 destroy 会话**（§8.13）。会话由 `SessionManager` + `ProotService` 保活，Activity 销毁只影响 UI。

**其他契约**

- `onNewIntent` 必须显式 `setIntent(intent)`，否则后续 `getIntent()` 仍返回最初启动它的 Intent，通知带过来的 action 会被丢掉（`singleTask` 下尤其容易踩）。收到 `ACTION_OPEN_TERMINAL` 时：有实例在跑就落到终端页并把焦点给一个正在跑的实例，否则回「我的系统」并提示。
- 卸载（`confirmUninstall`）：先 `stopIntent(this, id)` 只结束该实例 → `MultiPartDownloader.cleanupChunksFor` → `instanceStore.remove(id)` → 焦点若指向它则置 null → `manager.uninstall(id)` → 版本状态回 `Idle`。
- 存储位置切换（`onStorageSelected`）：先确认新位置可用，弹确认框，然后在「焦点实例在跑」时结束焦点实例 + 全部会话，再 `manager.setLocation(wanted)`。

### `LogActivity`

刷新 / 清空 / 复制 / 分享四个动作。分享走 `FileProvider.getUriForFile(this, "$packageName.fileprovider", file)`（authority 与 Manifest 的 `${applicationId}.fileprovider` 对齐，`file_paths.xml` 只暴露 `logs/`），并加 `FLAG_GRANT_READ_URI_PERMISSION`；同时把正文截前 30000 字符塞进 `EXTRA_TEXT`，兼容忽略附件的接收方。

---

## 17. 资源约定

| 类型 | 约定 |
| --- | --- |
| 布局 | 每屏一个 `view_*.xml`，由 `activity_main.xml` 用 `<include android:id=...>` 引入；列表项 `item_*.xml`；实例设置对话框 `dialog_instance_settings.xml` |
| 主题 | `AppTheme` 继承 `Theme.MaterialComponents.Light.NoActionBar`；按钮复用 `App.Button`（粉色圆角实心）/ `App.Key`（描边小按钮）/**`App.TerminalInput`（命令输入框，禁止改回密码框变体）** |
| 颜色 | 页面 `#F5F5F5`，强调粉 `#FFB6C1`，浅蓝 `#ADD8E6`，终端纯黑 + `#A8FF8A` 等宽字 |
| 图标 | 全部 vector drawable，无 PNG 位图 |
| 字符串 | 全部入 `strings.xml`；`MainActivity` 中仅少数动态状态文案硬编码（便于状态栏即时反馈） |

> ⚠️ `App.TerminalInput` 是 §8.15 的载体，属性组合见 `themes.xml` 的内联注释。
> 改成 `textPassword`/`textWebPassword` 会触发系统安全键盘，或让 `actionSend` 类
> `imeOptions` 抢走回车键——**命令输入需要能输入任意字符并用回车键换行**。

---

## 18. 构建与发布

- 插件：AGP `8.11.1` + Kotlin `2.0.21`；Gradle wrapper `8.13`（已入库）；JDK 17。
- 依赖：AppCompat 1.7.0 / Material 1.12.0 / DrawerLayout 1.2.0 / RecyclerView 1.3.2 / OkHttp 4.12.0 / Commons Compress 1.26.0 / xz 1.9 / coroutines 1.8.0。
- 必须保留的两个打包开关（否则 proot 无法执行）：
  - `app/build.gradle.kts` → `packaging { jniLibs { useLegacyPackaging = true } }`
  - `AndroidManifest.xml` → `android:extractNativeLibs="true"`（由上面的 Gradle DSL 在合并清单中合成，见 [COMPLIANCE.md](COMPLIANCE.md) §4.1）
- 签名优先级：环境变量 > `local.properties` > 兜底 `myapp.jks`；仅启用 V2/V3，关闭 V1/V4。
- CI（`.github/workflows/android.yml`）：tag push → 构建 + 自动 Release；`workflow_dispatch` → 仅 Artifact。**无 Fetch PRoot 步骤**，`.so` 已入库直接打包。
- Secrets：`KEYSTORE_BASE64`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`。

---

## 19. 改动检查清单

改代码前对照：

- [ ] 是否引入了 `java.nio.file` / `Files` / `toPath()`？（禁止）
- [ ] 是否用了 `Process.isAlive` / `waitFor(timeout)` / `destroyForcibly` 而没加 `SDK_INT >= O` 分支？
- [ ] 新增的 `.so` 是否以 `.so` 结尾？不以 `.so` 结尾的必须走 `assets/` + `NativeDeps`。
- [ ] 下载是否仍写 `.part` + `Range`？服务端不支持 Range（200）时是否**降级**而非失败？分块合并是否校验了总长度且不符就整份丢弃？
- [ ] 取消下载是否清理了**全部分块临时文件**（`<id>.<fmt>.part.<index>`）？
- [ ] 解压是否仍是「先 tmp 再 rename」？符号链接、权限位、路径穿越三项防护是否都还在？
- [ ] 是否新增了**无参的「当前会话」访问器**（`sessionOrNull()` 之类）？（§8.17 禁止）
- [ ] 会话状态/操作是否都带了实例 id？观察者回调是否按 id 过滤了？
- [ ] 是否有任何 `onDestroy` 调用了 `SessionManager.stop()` / `stopAll()`？（§8.13 禁止）
- [ ] 资源限制是否真的下发到 guest？**是否用 `toGuestCommand()` 追加 3 个独立 argv**（`/bin/sh`、`-c`、脚本），而不是把脚本当成单个 argv 传给 proot？`exec` 是否保留？0 是否表示不限制且不生成语句？MB→KB 是否 ×1024？
- [ ] 实例级配置是否写进了 `InstanceStore` 而不是全局 `AppPrefs` 或单例字段？（§8.17）
- [ ] 自定义镜像源是否做了 scheme 校验（只允许 http/https）？测速失败是否仍能下载（回退清单顺序）？
- [ ] 换源是否备份且**不覆盖已有备份**？是否支持恢复且找不到备份时如实报错？写文件是否原子？
- [ ] 清单字段是否只增不改？新增发行版是否**没有**动下载/解压核心逻辑？
- [ ] 分享是否仍走 `content://`？崩溃路径是否仍先写 `AppLogger` 再结束进程？
- [ ] 包名是否三处一致 `com.li63050a.linuxandroid`？
