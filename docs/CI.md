# 持续集成（CI）详解

本文件说明 `.github/workflows/android.yml` 的**触发条件、执行步骤、Secrets 配置、
失败排查与本地复现**。

> 发版流程见 [RELEASE.md](RELEASE.md)；本地构建见 [CONTRIBUTING.md](CONTRIBUTING.md) §2。

---

## 1. 触发条件

```yaml
on:
  push:
    branches: [beta]     # 分支推送 → 只构建
    tags: ['v*']         # 打 tag   → 构建 + 正式发行
  workflow_dispatch:      # 手动触发 → 可选发行
    inputs:
      release: 'false' | 'true'
```

| 事件 | 构建 | 发行 | tag 形态 |
| --- | --- | --- | --- |
| push 到 `beta` | ✅ | ❌ | — |
| push tag `v*` | ✅ | ✅ **正式** | 你打的 tag |
| 手动 + `release=false` | ✅ | ❌ | — |
| 手动 + `release=true` | ✅ | ✅ **预发布** | `beta-<构建号>` |

⚠️ **`main` 未配置自动构建。** 只有 `beta` 在 `on.push.branches` 里。
不要假设「推默认分支就会构建」——想启用必须显式加进去。

⚠️ tag 必须匹配 `v*`。写成 `0.0.0.4`（没有 `v` 前缀）**不会**触发任何东西。

---

## 2. 执行步骤

工作流只有一个 job（`Build & (maybe) Release`），12 个步骤：

| # | 步骤 | 作用 |
| ---: | --- | --- |
| 1 | Checkout repository | 拉取代码 |
| 2 | Set up JDK 17 | Temurin JDK 17 + **Gradle 缓存** |
| 3 | Grant execute permission for gradlew | Windows 提交的仓库会丢可执行位 |
| 4 | **Static constraint check (§8)** | 编译**之前**拦截硬约束违规 |
| 5 | Verify PRoot binaries are present | 确认 4 个 `.so` 都在（§8.9） |
| 6 | Accept Android SDK Licenses | 接受预装 SDK 的许可证 |
| 7 | Decode Keystore | 从 Secrets 解出 `.jks`；缺省则标记回退 |
| 8 | Build Release APK | `./gradlew assembleRelease`（有签名时） |
| 9 | Build Debug APK | `./gradlew assembleDebug`（无签名时） |
| 10 | Collect APK | 重命名为自识别文件名 |
| 11 | Upload APK as Artifact | 保留 30 天 |
| 12 | Create GitHub Release | 仅 tag 或手动勾选时执行 |

### 2.1 第 4 步：静态约束检查

在**编译之前**运行，把明显违规拦在打包之前。当前检查 5 项：

| 检查 | 约束 |
| --- | --- |
| `java.nio` / `toPath()` 不得出现 | §8.6 |
| 不得申请存储权限 | §8.2 |
| 不得使用 SAF（`ACTION_OPEN_DOCUMENT_TREE`） | §8.2 |
| 不得残留 `com.example` | §8.5 |
| 终端输入框不得用 `textPassword` / `textWebPassword` | §8.15 |

> 📌 **新增硬约束时应同步往这里加检查**，否则约束只停留在文档上。
> 加检查只需在 `check_empty` 列表里加一行。

### 2.2 第 5 步：校验二进制

```
app/src/main/jniLibs/arm64-v8a/libproot.so
app/src/main/jniLibs/arm64-v8a/libproot-loader.so
app/src/main/assets/native/arm64-v8a/libtalloc.so.2
app/src/main/assets/native/arm64-v8a/libandroid-shmem.so
```

这 4 个文件**必须入库**（§8.9）。缺少时 CI 会明确报出是哪一个，
而不是等到运行时才发现 proot 起不来。

> `libtalloc.so.2` 与 `libandroid-shmem.so` 的名字不以 `.so` 结尾，
> Android 不会把它们当 JNI 库自动解压，因此放在 `assets/` 由 `NativeDeps` 释放。

### 2.3 第 10 步：APK 命名

```
ProotTerm-v<versionName>-<release|debug>-<短SHA>.apk
例：ProotTerm-v0.0.0.1-release-f870996.apk
```

原先直接用 Gradle 输出的 `app-release.apk`，多次下载会互相覆盖且无法辨认版本。
`versionName` 从 `app/build.gradle.kts` 里读。

---

## 3. Secrets

| Secret | 必需 | 内容 |
| --- | --- | --- |
| `KEYSTORE_BASE64` | 正式发行必需 | `.jks` 的 base64 |
| `KEYSTORE_PASSWORD` | 同上 | keystore 口令 |
| `KEY_ALIAS` | 同上 | 密钥别名 |
| `KEY_PASSWORD` | 同上 | 密钥口令 |
| `GITHUB_TOKEN` | 自动提供 | 创建 Release 用，无需配置 |

生成 base64：

```bash
base64 -w0 my.jks     # Linux
base64 -i my.jks      # macOS
```

**未配置 `KEYSTORE_BASE64` 时 CI 不会失败**，而是回退构建 debug 包，
并在 Release Notes 里插入警告。这是为了让 fork 仓库也能跑通 CI。
但 **debug 包不可用于正式分发**。

---

## 4. 为什么 CI 是必要的（本项目的真实教训）

**开发机是 x86_64 Linux，没有 Android SDK，无法编译。** 因此本地只能做静态检查
（grep、括号平衡、资源交叉引用）。这类检查有系统性盲区。

实际发生过的事：某次提交通过了**全部**本地静态检查，包括括号平衡、
`R.id` 交叉校验、§8 约束 grep，但 CI 首次真实编译连续失败三轮：

1. `android:autoCorrect` —— 不是 Android 框架属性，AAPT 链接失败；
2. 表达式体函数内用 `return` —— Kotlin 语法错误；
3. `Set.symmetricDifference` —— Kotlin 1.9+ 实验性 API，2.0.21 下解析歧义。

**这三个错误静态检查一个都发现不了。** 结论：改 Kotlin 或资源文件后，
**必须等 CI 变绿再打 tag**。

---

## 5. 失败排查

### 5.1 定位错误

```bash
# 看最近几次运行
gh run list --limit 5

# 看某次运行的日志
gh run view <run-id> --log-failed
```

没有 `gh` 就在网页上点进失败的 job。

### 5.2 按失败步骤对照

| 失败步骤 | 常见原因 | 处理 |
| --- | --- | --- |
| Static constraint check | 提交违反 §8.2 / §8.5 / §8.6 / §8.15 | 按 `::error::` 提示定位 |
| Verify PRoot binaries | `.so` 没入库或被 `.gitignore` 排除 | 见 §2.2 的 4 个路径 |
| Decode Keystore | `KEYSTORE_BASE64` 不是合法 base64 | 重新 `base64 -w0` 生成 |
| Build Release APK | AAPT 资源错误 / Kotlin 编译错误 | 见 §4 的三类典型 |
| Create GitHub Release | 缺 `contents: write` 权限 | 工作流已声明，若改过请确认 |

### 5.3 典型编译错误

**AAPT 资源链接失败**

```
error: style attribute 'android:attr/xxx' not found.
error: failed linking references.
```

多半是用了**不存在的框架属性**。注意：IDE 补全提示的属性不一定存在于
`android.jar`（例如 `android:autoCorrect` 只是 IDE 建议）。

**Kotlin 编译错误**

```
e: file:///.../Xxx.kt:12:34 Unresolved reference 'foo'
```

常见于引用了**比项目 Kotlin 版本更新的 API**。本项目用 Kotlin 2.0.21，
`Set.symmetricDifference` 之类 1.9+ 实验性 API 会解析歧义。

---

## 6. 本地复现 CI

没有 SDK 时**无法**完整复现，但可以复现能复现的部分：

```bash
# 1. 静态约束检查（与 CI 第 4 步一致）
grep -rn "java\.nio\|toPath()" app/src/main/java/          # 必须为空
grep -rn "READ_EXTERNAL_STORAGE\|WRITE_EXTERNAL_STORAGE\|MANAGE_EXTERNAL_STORAGE" app/src/main/
grep -rn "ACTION_OPEN_DOCUMENT_TREE" app/src/main/java/    # 必须为空
grep -rn "com\.example" app/src/main/java/                 # 必须为空
grep -n "textPassword\|textWebPassword" app/src/main/res/layout/view_terminal.xml

# 2. 二进制在位检查（与 CI 第 5 步一致）
for f in app/src/main/jniLibs/arm64-v8a/libproot.so \
         app/src/main/jniLibs/arm64-v8a/libproot-loader.so \
         app/src/main/assets/native/arm64-v8a/libtalloc.so.2 \
         app/src/main/assets/native/arm64-v8a/libandroid-shmem.so; do
  [ -f "$f" ] && echo "OK $f" || echo "缺失 $f"
done

# 3. 资源交叉校验（CI 未做，但很有用）
cd app/src/main
comm -23 <(grep -rhoP 'R\.id\.\w+' java/ | sed 's/R\.id\.//' | sort -u) \
         <(grep -rhoP 'android:id="@\+id/\w+"' res/ | sed 's/.*@+id\///;s/"//' | sort -u)
```

有 SDK 的机器上再补：

```bash
./gradlew assembleRelease    # 与 CI 第 8 步一致
```

---

## 7. 修改工作流的注意事项

- **改 `on.push.branches` 前想清楚**：加 `main` 意味着每次推 `main` 都会构建；
  若同时改了发行条件，可能造成意外发行。
- **不要给 `beta` 加自动发行**：分支推送会频繁触发，Release 会迅速堆积。
- **发行条件保持显式**：当前是 `github.ref_type == 'tag' || inputs.release == 'true'`，
  两个条件都很好审计。不要改成模糊判断。
- **`permissions: contents: write` 是创建 Release 必需的**，删掉会导致第 12 步失败。
- 改完在本地校验 YAML：

  ```bash
  python3 -c "import yaml;yaml.safe_load(open('.github/workflows/android.yml'))" && echo OK
  ```

---

## 8. 相关文档

| 文档 | 内容 |
| --- | --- |
| [RELEASE.md](RELEASE.md) | 发版操作手册 |
| [CHANGELOG.md](../CHANGELOG.md) | 历史版本变化 |
| [CONTRIBUTING.md](CONTRIBUTING.md) §2 | 本地环境与构建 |
| [AGENTS.md](../AGENTS.md) §7 | 构建与验证约定 |
