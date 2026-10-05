# 项目文档索引

> LinuxAndroid（ProotTerm）的文档导航。
> **第一次来请先读 [GLOSSARY.md](GLOSSARY.md)（名词解释）再读
> [CONTRIBUTING.md](CONTRIBUTING.md)（怎么干活）。**

---

## 按角色选文档

| 我是…… | 建议阅读顺序 |
| --- | --- |
| **完全的新人** | [GLOSSARY.md](GLOSSARY.md) → [../README.md](../README.md) → [CONTRIBUTING.md](CONTRIBUTING.md) |
| **新贡献者** | [CONTRIBUTING.md](CONTRIBUTING.md) → [AGENTS.md](../AGENTS.md) §8 → [ARCHITECTURE.md](ARCHITECTURE.md) |
| **要改会话 / 后台 / 自启动** | [ARCHITECTURE.md](ARCHITECTURE.md) §13–15 → [MODULES.md](MODULES.md) §10–12 → [TESTING.md](TESTING.md) §7.4/7.5 |
| **要改某个模块** | [MODULES.md](MODULES.md) → 对应类的章节 → [TESTING.md](TESTING.md) |
| **要加发行版/版本** | [ARCHITECTURE.md](ARCHITECTURE.md) §12 扩展指南 → `assets/rootfs_manifest.json` |
| **遇到问题** | [TROUBLESHOOTING.md](TROUBLESHOOTING.md) |
| **要验证改动** | [TESTING.md](TESTING.md) |
| **要发版** | [RELEASE.md](RELEASE.md) → [CHANGELOG.md](../CHANGELOG.md) |
| **CI 挂了 / 要改工作流** | [CI.md](CI.md) |
| **想了解改了什么** | [CHANGELOG.md](../CHANGELOG.md) |
| **普通用户** | [../README.md](../README.md) |
| **做合规审查** | [COMPLIANCE.md](COMPLIANCE.md) → [AGENTS.md](../AGENTS.md) §8 |

---

## 文档清单

### 根目录

| 文档 | 行数 | 定位 | 读者 |
| --- | ---: | --- | --- |
| [README.md](../README.md) | 253 | 项目介绍、构建、使用方法、FAQ、工作原理简述 | 用户 |
| [CHANGELOG.md](../CHANGELOG.md) | 181 | 版本变更日志、版本号约定、已知限制 | 用户 / 维护者 |
| [AGENTS.md](../AGENTS.md) | 514 | **编码约定与 21 条硬性约束**（权威，改代码前必读） | AI / 开发者 |

> ⚠️ **AGENTS.md §8 是硬性约束**，不是建议。违反会导致行为错误或安全问题。
> 21 条摘要见本文件末尾。

### docs/

| 文档 | 行数 | 定位 |
| --- | ---: | --- |
| [GLOSSARY.md](GLOSSARY.md) | 212 | **术语表**：PRoot / rootfs / ulimit / deb822 / FUSE / ABI 等名词解释（新人先看这个） |
| [CONTRIBUTING.md](CONTRIBUTING.md) | 351 | 开发者入门：环境准备、代码地图、工作流、提交前自检 |
| [CI.md](CI.md) | 241 | 持续集成详解：触发条件、12 个步骤、Secrets、失败排查、本地复现 |
| [RELEASE.md](RELEASE.md) | 217 | **发版手册**：提升版本号、打 tag、检查 Release、回滚 |
| [ARCHITECTURE.md](ARCHITECTURE.md) | 1064 | 深度架构：分层、九条核心链路、状态机、**多实例会话架构**、**资源限制**、**镜像优选与分块下载**、**APT 换源**、存储位置、自启动、设计决策、扩展指南、风险清单 |
| [MODULES.md](MODULES.md) | 1088 | 逐类 API 契约与坑（26 个 Kotlin 文件，6186 行） |
| [TESTING.md](TESTING.md) | 1209 | 测试策略、静态检查、手工测试矩阵（多实例 MI / 资源限制 RL / 镜像 MX / 分块 MP / 换源 AS / 实例配置 IC）、回归清单 |
| [TROUBLESHOOTING.md](TROUBLESHOOTING.md) | 1329 | 按症状索引的故障排查手册（含日志标签速查、外部存储 noexec、通知缺失等新增条目） |
| [COMPLIANCE.md](COMPLIANCE.md) | 220 | 对 AGENTS.md §8 的 **21 条**逐条合规审查报告 |

---

## 21 条硬性约束（摘要）

完整表述与理由见 [AGENTS.md §8](../AGENTS.md)。

| # | 约束 | 一句话 |
| --- | --- | --- |
| 1 | UI 技术栈 | AppCompat + Material，XML 布局，**不引入 Compose** |
| 2 | 权限 | 不申请存储权限、不用 SAF；只允许内部 `/data/data/<pkg>` 与外部 `/Android/data/<pkg>` 两个私有沙箱 |
| 3 | native 库打包 | `useLegacyPackaging = true`（保证 `.so` 可执行） |
| 4 | ABI | 仅 `arm64-v8a` |
| 5 | 包名 | 三处一致 `com.li63050a.linuxandroid` |
| 6 | 禁用 API | **禁 `java.nio.file`**；Process 的 26+ API 必须 SDK_INT 分支 |
| 7 | 可靠性 | 下载 `.part` + Range；解压先 tmp 再 rename；链接/权限/防穿越三者缺一不可 |
| 8 | PRoot 环境 | `PROOT_TMP_DIR` / `PROOT_LOADER` / `LD_LIBRARY_PATH` 必须绝对路径 |
| 9 | 二进制入库 | `jniLibs/**/*.so` 与 `assets/native/**` 必须提交 |
| 10 | 扩展方式 | 新增发行版只改清单；已废弃的自定义发行版 FAB 禁止加回 |
| 11 | 远程仓库 | `https://github.com/LiStudioorg/linuxandroid.git`（协议变更需维护者确认） |
| 12 | 分享与崩溃 | 日志分享用 FileProvider `content://`；崩溃先写日志再退出 |
| 13 | 会话归属 | **Activity 不得持有 shell 进程**；会话归 `SessionManager` + `ProotService` |
| 14 | 关键路径 | `PROOT_TMP_DIR`/`logs`/`native` 固定内部，不随存储位置变化（外部不可执行） |
| 15 | 安全键盘 | 命令输入框必须用 `App.TerminalInput`（`textVisiblePassword`，禁 `textPassword`） |
| 16 | 快捷键 | 终端功能键必须发真实控制序列，禁止空壳按钮 |
| 17 | 多实例隔离 | 每实例独立 `InstanceConfig`（存 `instances.json`，**不写 `AppPrefs`**）；每槽独立 `epoch`；**禁止**无参「当前会话」访问器 |
| 18 | 资源限制 | `ulimit` 语义（cgroup 免 root 不可得）；**必须 3 个独立 argv**（单字符串会让受限会话全部起不来）；脚本末尾必须有 `exec`；`0` = 不限制 |
| 19 | 下载器 | 分块需 HTTP 206（200 则降级单线程）；合并校验总长；复用需长度 + `.src` 来源标记；取消要清分块 |
| 20 | 镜像优选 | 并发测速 + 超时有上限；**全部失败必须回退清单顺序**；自定义源仅 http/https；SHA256 才是信任来源 |
| 21 | APT 换源 | 备份固定后缀且**不被覆盖**；必须可恢复；deb822 / legacy / Alpine 三格式；写文件必须原子 |

---

## 项目速览

| 项 | 值 |
| --- | --- |
| 包名 / 品牌名 | `com.li63050a.linuxandroid` / **ProotTerm** |
| 版本 | versionCode 1 / versionName `0.0.0.1` |
| 语言 / 界面 | Kotlin 100% / XML + AppCompat + Material（无 Compose） |
| 构建链 | AGP 8.11.1、Kotlin 2.0.21、Gradle 8.13、JDK 17 |
| SDK | compileSdk / targetSdk 36，**minSdk 24** |
| ABI | 仅 `arm64-v8a` |
| 代码量 | 26 个 Kotlin 文件、6186 行 |
| 存储位置 | 内部 `/data/data/<pkg>` ↔ 外部 `/Android/data/<pkg>`（均免权限） |
| 会话存活 | 前台服务 `ProotService` + 常驻通知；支持开机自启动；Activity 只是观察者 |
| 同 IP | PRoot 共享宿主内核网络栈，guest 天然拥有手机 IP（无需任何代码） |

### 九条核心链路

```
A 下载安装  .part + Range 续传 → 多镜像回退 → SHA256 → 解压 tmp → 原子 rename → .installed
B 解压      tar.gz/tar.xz → 防路径穿越 + 保符号链接 + 保权限位（API 24 安全）
C PRoot     libproot.so -r <rootfs> -0 -b /dev,/proc,/sys + 三个绝对路径环境变量
D 会话保活  SessionManager 多会话注册表 + ProotService 前台服务；Activity 只做观察者
E 存储位置  StorageLocation 内部/外部私有沙箱；切换只影响后续安装
F 开机自启  BootReceiver → ProotService（校验全在服务里做）
G 资源限制  ResourceLimits → /bin/sh -c 'ulimit …; exec <shell>'（3 个独立 argv）
H 镜像优选  MirrorProbeService 并发测速 → 失败回退清单顺序 → MultiPartDownloader 分块并发
I 换源      AptSourceSwitcher：deb822 / legacy / Alpine，备份可逆、写入原子
```

详见 [ARCHITECTURE.md](ARCHITECTURE.md)。

---

## 文档维护约定

1. **单一事实来源**：同一事实只在一处详述，其他地方链接过去。
2. **改接口先改 [MODULES.md](MODULES.md)**，改设计先改 [ARCHITECTURE.md](ARCHITECTURE.md)。
3. **约束以 [AGENTS.md](../AGENTS.md) 为准**；若代码与约束不符，要么改代码，要么明确改约束（并在 [COMPLIANCE.md](COMPLIANCE.md) 留档）。
4. **新增风险/待办**写进 [ARCHITECTURE.md](ARCHITECTURE.md) §19 或 [COMPLIANCE.md](COMPLIANCE.md) §5。

> ✅ **`docs/` 已纳入版本控制**。修改文档后请与代码一同提交，不要只留在本地工作区。
