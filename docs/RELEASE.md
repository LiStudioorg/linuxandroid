# 发行手册

本文件说明**如何发布一个 ProotTerm 版本**：提升版本号 → 打 tag → 等待 CI →
检查 Release → 回滚。

> 改代码的约定见 [AGENTS.md](../AGENTS.md) §8；
> CI 工作机制见 [CI.md](CI.md)；历史版本见 [CHANGELOG.md](../CHANGELOG.md)。

---

## 1. 发行模型

| 分支 / 事件 | 自动构建 | 自动发行 | 产物 |
| --- | --- | --- | --- |
| push 到 `beta` | ✅ | ❌ | Artifact（保留 30 天） |
| push tag `v*` | ✅ | ✅ **正式** Release | Release 附 APK |
| 手动 `workflow_dispatch` | ✅ | 勾选后为**预发布** | Release 附 APK |

**`beta` 是集成分支，`main` 尚未启用自动构建。** 工作流的
`on.push.branches` 只列了 `beta`——不要依赖默认分支行为，想改必须显式修改。

### 为什么日常提交不发 Release

每推一个提交就发一个 Release 会污染 Releases 页面，用户无法分辨哪个是稳定版。
因此：**平时往 `beta` 推，只构建不发行；要发行才打 tag。**

---

## 2. 发版步骤

### 步骤 1：确认在 `beta` 上且 CI 是绿的

```bash
git branch --show-current          # 必须是 beta
git status --short                 # 应该干净
git log --oneline -5
```

到 GitHub Actions 页面确认 `beta` 最新一次 push 是 ✅。
**构建红的版本不要打 tag**——tag 会立刻触发发行，把坏包发出去。

### 步骤 2：提升版本号（**最容易漏的一步**）

编辑 `app/build.gradle.kts`：

```kotlin
versionCode = 2          // 必须单调递增！
versionName = "0.0.0.4"  // 与即将打的 tag 一致
```

- `versionCode` 是整数，**每次发版都要 +1**。不递增的话 Android 会拒绝覆盖安装
  （报 `INSTALL_FAILED_VERSION_DOWNGRADE`），用户必须先卸载，会丢数据。
- `versionName` 要与 tag 对应（tag `v0.0.0.4` ↔ `versionName "0.0.0.4"`）。
- APK 文件名取自 `versionName`，所以版本号不对会导致文件名与 tag 不符。

> ⚠️ **历史遗留问题**：截至撰写时 `versionCode` 仍为 `1`、`versionName` 仍为
> `"0.0.0.1"`，但 `v0.0.0.1` ~ `v0.0.0.3` 三个 tag 已存在。
> 下一次发版必须先修正这组数字。

### 步骤 3：更新 CHANGELOG

把 [CHANGELOG.md](../CHANGELOG.md) 顶部的 `[未发布]` 段落改成本次版本号与日期，
并在上方新开一个空的 `[未发布]`。

### 步骤 4：提交并推送

```bash
git add app/build.gradle.kts CHANGELOG.md
git commit -m "release: v0.0.0.4"
git push origin beta
```

等这次 push 的 CI 变绿——它验证的是**将要被打 tag 的那个提交**。

### 步骤 5：打 tag 并推送

```bash
git tag -a v0.0.0.4 -m "ProotTerm v0.0.0.4"
git push origin v0.0.0.4
```

> ⚠️ **只推单个 tag**，不要用 `git push --tags`——那会把所有本地 tag 一起推上去，
> 每个都会触发一次发行。

### 步骤 6：检查 Release

CI 跑完后到 Releases 页面确认：

- tag 名与 `versionName` 一致；
- 附带的 APK 文件名形如 `ProotTerm-v0.0.0.4-release-<短SHA>.apk`；
- **不是** `prerelease`（tag 触发的一律为正式发行）；
- Release Notes 里没有「本次为 debug 包」的警告——有的话说明签名 Secrets 没配好，
  见 §4。

### 步骤 7：合并回 `main`（在 `main` 启用自动构建之前，属手工步骤）

```bash
git checkout main
git merge --ff-only beta
git push origin main
```

---

## 3. 手动触发（不发正式版）

用于「想立刻拿到一个包试试」而**不想**占用版本号：

```
GitHub → Actions → Build Android APK → Run workflow
  Branch: beta
  release: false   ← 默认，只产出 Artifact
```

若把 `release` 选成 `true`，会创建**预发布**，tag 形如 `beta-<构建号>`，
且会自动标记为 prerelease（因为 `github.ref_type != 'tag'`）。

> ⚠️ 手动触发的预发布**不会**提升版本号，产物文件名里仍是当前 `versionName`。
> 仅供测试，不要分发给用户。

---

## 4. 签名

正式发行需要仓库配置 4 个 Secrets：

| Secret | 内容 |
| --- | --- |
| `KEYSTORE_BASE64` | `.jks` 文件的 base64（`base64 -w0 my.jks`） |
| `KEYSTORE_PASSWORD` | keystore 口令 |
| `KEY_ALIAS` | 密钥别名 |
| `KEY_PASSWORD` | 密钥口令 |

**未配置 `KEYSTORE_BASE64` 时**，CI 不会失败，而是回退构建 **debug 包**，
并在 Release Notes 里插入醒目警告。这样做是为了让 fork 仓库也能跑通 CI，
但 **debug 包不可用于正式分发**（用 debug key 签名，无法与正式包相互覆盖安装）。

构建侧读取顺序（`app/build.gradle.kts`）：
环境变量（CI 注入）> `local.properties`（本地）> 兜底 `myapp.jks`。

> 🔒 keystore 绝不能入库。`.gitignore` 已排除 `*.jks` / `*.keystore` /
> `*_base64.txt`；提交前请再次确认。

---

## 5. 回滚

### 5.1 撤销一次错误的 Release

Release 已发布后**不要删 tag 了事**——已经有人可能下载了。推荐做法：

1. 把该 Release 标记为 **prerelease**（或直接删除 Release，但保留 tag），
   避免继续被当作稳定版分发；
2. 在 Release Notes 顶部加上说明；
3. 修复后发一个**更高的** `versionCode`。**不要复用已发布的版本号**。

### 5.2 删除刚刚推送的 tag

仅在**尚未有人下载**、且确实需要重打时：

```bash
git tag -d v0.0.0.4                  # 删本地
git push origin :refs/tags/v0.0.0.4  # 删远程
```

然后到 Releases 页面删除对应的 Release。注意：远程 tag 删除后，
已产生的 Release 可能仍指向旧提交，需手动清理。

### 5.3 回滚 `beta` 上的代码

```bash
git revert <坏提交的SHA>   # 生成一个反向提交，保留历史
git push origin beta
```

优先用 `revert` 而非 `reset --force`——`beta` 是共享分支，
强推会让其他人的本地副本错乱。

---

## 6. 发布前检查清单

- [ ] `beta` 上最新 CI 为绿色
- [ ] `versionCode` 已递增（且大于所有已发布版本）
- [ ] `versionName` 与即将打的 tag 一致
- [ ] `CHANGELOG.md` 已把 `[未发布]` 改为本次版本号 + 日期
- [ ] 4 个签名 Secrets 已配置（否则会发成 debug 包）
- [ ] **已在 arm64 真机上验证过**：多实例并行、资源限制会话启动、
      换源后 `apt update`（CI 只证明「能编译」，不证明「能跑」）
- [ ] 本次没有把 `.jks` / `local.properties` 提交进去
- [ ] 用**单个** tag 推送，不用 `--tags`

---

## 7. 常见问题

| 现象 | 原因与处理 |
| --- | --- |
| 用户装不上，报 `INSTALL_FAILED_VERSION_DOWNGRADE` | `versionCode` 没递增。提升后重发 |
| 用户装不上，报「应用未安装」/签名冲突 | 用了 debug 包，或换了 keystore。正式包必须始终用同一 keystore 签名 |
| APK 文件名里的版本与 tag 不符 | `versionName` 没跟着 tag 改 |
| Release 里带「本次为 debug 包」警告 | `KEYSTORE_BASE64` 未配置或 base64 解出来是坏文件 |
| tag 推上去了但没触发 CI | tag 名不匹配 `v*`（例如写成 `0.0.0.4` 没有 `v` 前缀） |
| 一次推送触发了多个 Release | 用了 `git push --tags`。删掉多余 Release 与 tag，改用单个 tag 推送 |
| CI 在「§8 静态约束检查」步骤失败 | 提交违反了 AGENTS.md §8 的硬约束，按报错信息定位 |
| CI 在编译步骤失败 | 本地无 SDK 时静态检查发现不了，属正常；按日志修（见 [CI.md](CI.md)） |

---

## 8. 相关文档

| 文档 | 内容 |
| --- | --- |
| [CHANGELOG.md](../CHANGELOG.md) | 历史版本变化与版本号约定 |
| [CI.md](CI.md) | 工作流详解、Secrets、失败排查、本地复现 |
| [TESTING.md](TESTING.md) | 发布前应在真机上跑的测试矩阵 |
| [TROUBLESHOOTING.md](TROUBLESHOOTING.md) | 用户侧问题排查 |
