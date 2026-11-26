# Minis Agent

本地优先的 Android AI Agent。模型负责决策，设备负责执行：读写文件、执行 Shell、控制浏览器、调用 MCP、维护记忆，全部发生在手机上，运行环境是一个跑在 PRoot 里的 Ubuntu 24.04。

**中文** · [English](#english)

---

## 版本

| 项目 | 值 |
|:--|:--|
| 包名 | `com.openminis.app` |
| 版本 | `2.0-final-ubuntu24.04-minis`（versionCode 92） |
| SDK | compileSdk 36 · targetSdk 35 · minSdk 26 |
| 技术栈 | Kotlin 2.1.0 · Jetpack Compose · Material 3 |
| 构建链 | Gradle 8.11.1 · AGP 8.7.3 · JDK 17 |
| 发布产物 | `Minis-Agent-2.0-final.apk`（102,524,665 字节） |
| SHA-256 | `b3fc2293bc4a8b4ce6e77e20304f4d96662b91603540568702a4b2339e37d768` |
| 产物来源 | 提交 `b6424dd` 的 CI 构建，`assemble` 与 `unit-tests` 均通过 |

## 工作方式

一次请求进来后，Agent 循环四步：规划下一步、调用工具、读取结果、修正判断。循环本身不复杂，难点在四件事——工具结果太长会挤爆上下文，模型会原地打转，危险命令会毁掉用户数据，执行中断后状态无法回退。这一版主要是在这四件事上做工程。

工具在设备侧真实执行：`file_edit` 改的是沙箱里的文件，`shell_execute` 跑在 PRoot 的 Ubuntu 环境里，`browser_use` 通过可访问性树操作页面，MCP 服务按配置直连。所有写操作执行前会被检查点记录，出错可按文件或全量回滚。

## 架构

| 层 | 职责 | 主要入口 |
|:--|:--|:--|
| 表现层 | 会话界面、工具卡片、设置页 | `ui/chat`、`ui/settings` |
| 会话层 | 消息块与流式渲染、上下文裁剪、检查点与审批挂载 | `ChatViewModel` |
| 决策层 | Agent 循环、工具调度、循环检测、子代理预算、结果治理 | `agent/`、`tools/` |
| 执行层 | 沙箱、Shell、终端、浏览器、可访问性 | `sandbox/`、`terminal/`、`browser/` |
| 支撑层 | 持久化、模型供应商、记忆、技能、MCP | `data/`、`provider/`、`memory/`、`skills/`、`mcp/` |

## 模块

| 路径 | 内容 |
|:--|:--|
| `ui/chat` | 会话与工具卡渲染，diff 分段展示（`ToolDiffCard`、`DiffPreviewParser`） |
| `tools` | 工具定义与执行，补丁拆分（`PatchSplitter`），检查点回滚工具 |
| `agent` | 循环检测、子代理预算、命令风险与审批协调 |
| `sandbox` | PRoot 沙箱、Shell 超时策略、写入检查点、输出落盘 |
| `memory` | 事实存储与混合检索索引（`HybridMemoryIndex`） |
| `mcp` | MCP 网关与工具注册表（预热、上限、节流） |
| `skills` | 技能加载与远程源校验（https、白名单、哈希、拒降级） |
| `browser` | 可访问性树驱动的页面操作与屏幕树增量对比 |
| `provider` | 模型供应商适配，流式解析与 OAuth 授权 |
| `data` | Room 持久化，消息 parts JSON，会话与设置 |
| `speech` | 录音、语音识别与朗读 |
| `observability` | 工具调用事件流（NDJSON）与导出 |

## 工程细节

**结果治理**　执行结果先过 `ToolResultTrim`：留首尾、保错误词、折叠连续重复行，超限部分记 `omittedChars` 后写入磁盘，模型可再取回。只读工具的结果进入 LRU 缓存（16 条 / 2 MB / 5 分钟），命中即跳过执行。

**循环检测**　`ToolProgressSignature` 把时间戳、UUID、进度百分比归一，同一动作不会因为噪声被当成新动作；`ToolLoopDetector` 在归一化签名上判重。

**超时**　`ShellTimeoutPolicy` 按 `&& || ; |` 拆段，取各段所需超时的最大值，避免长任务被短超时误杀。

**审批**　`CommandRiskPolicy` 分三档：安全、警告、阻断。阻断项不可绕过；警告项走 `ApprovalCoordinator` 的挂起卡片，用户可选本次会话记住（6 小时）或始终允许，否则拒绝；60 秒无响应即按拒绝处理，超时后的点击作废，拒绝时回给模型的是一段"换更安全方案"的指令，而不是一句报错。

**检查点**　`SessionCheckpoint` 在每次写文件前记录旧内容，重复写同一文件保留最早版本，按文件数与字节上限淘汰最旧记录；`checkpoint_restore` 支持单文件或全量回滚。

**检索**　`HybridMemoryIndex` 是纯 Kotlin 实现：中文切单字与双字 bigram，拉丁按整词，BM25（k1=1.2、b=0.75）与 128 维稀疏哈希向量余弦按 0.65 / 0.35 融合，多路结果用 RRF（k=60）合并。

**MCP**　除 `mcp_call` 统一网关外，模型也可直接调 `mcp__server__tool`；注册表在启动时预热，单服务上限 20 个工具、全局 48 个，刷新节流 120 秒。

**远程技能**　只接受 https，主机白名单，SHA-256 为 64 位十六进制，包体上限 32 MB，版本号只升不降；`RemoteSourcePolicy` 另拒 http、localhost、私网地址、带凭据 URL 与异常端口。

**分段编辑**　一次编辑拆成 hunk，diff 卡片按旧行号跳变分组，绿增红删、逐段勾选；确认后只写回选中段，工具卡摘要同步更新并写回消息 parts JSON，重启后仍在。

## 构建

```bash
# 需要 JDK 17、Android SDK 36，构建 native 桥接还需 NDK
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

CI 分两个任务：`assemble` 是门禁，必须通过；`unit-tests` 独立运行并上传报告，不绑定构建结论。Gradle 缓存与镜像源用来抵消依赖下载抖动。

## 测试

746 个测试类，覆盖工具、沙箱、审批、检查点、索引、UI 解析与端到端流程。依赖网络、真机或供应商凭据的用例由 `ExternalSourcesRule` 守卫，条件缺失时跳过而非失败，当前跳过约 214 个。

源码曾整体去注释，期间误伤过 native 预处理指令与测试锚点，已恢复 68 处结构标记，并以"忽略注释的 token 哈希"对 2257 个文件做过全量比对，确认无代码丢失。

## 已知限制

1. 发布用 debug 签名，适合自测与内部试用；对外分发需换自有 keystore 重签。
2. `HybridMemoryIndex` 目前是独立可测的索引层，默认召回路径仍走原实现。
3. 首次解压 rootfs 有明显等待，低端机更慢。
4. 部分厂商 ROM 限制后台进程与可访问性服务，需手动授权并关闭电池优化。

## 目录

```
app/
├── build.gradle.kts                模块配置、flavor、测试与镜像
├── libs/                           预置依赖（rclone.aar）
└── src/
    ├── main/
    │   ├── java/com/openminis/app/  Kotlin 源码，38 个功能包
    │   ├── cpp/                     native 桥接：PTY、崩溃处理、分词
    │   ├── assets/                  rootfs、结巴词典、KaTeX 字体、页面资源
    │   ├── jniLibs/arm64-v8a/       PRoot 动态库
    │   └── res/                     布局、图标、字符串
    └── test/java/com/openminis/app/ 单元测试与基准
.github/workflows/android.yml       分层 CI
gradle/                             版本目录与 wrapper
```

---

<h2 id="english">English</h2>

A local-first AI agent for Android. The model decides, the device executes: file edits, shell commands, browser control, MCP calls and long-term memory all happen on the phone, inside an Ubuntu 24.04 environment running under PRoot.

## Release

| Item | Value |
|:--|:--|
| Application ID | `com.openminis.app` |
| Version | `2.0-final-ubuntu24.04-minis` (versionCode 92) |
| SDK | compileSdk 36 · targetSdk 35 · minSdk 26 |
| Stack | Kotlin 2.1.0 · Jetpack Compose · Material 3 |
| Toolchain | Gradle 8.11.1 · AGP 8.7.3 · JDK 17 |
| Artifact | `Minis-Agent-2.0-final.apk` (102,524,665 bytes) |
| SHA-256 | `b3fc2293bc4a8b4ce6e77e20304f4d96662b91603540568702a4b2339e37d768` |
| Built from | commit `b6424dd`, CI `assemble` and `unit-tests` both green |

## How it works

Each request drives a four-step loop: plan the next move, call a tool, read the result, revise. The loop itself is trivial. The difficulty sits in four places — tool output floods the context window, models repeat themselves, risky commands can destroy user data, and an interrupted run leaves state you cannot undo. Most of this release is engineering around those four.

Tools run on the device rather than in a service: `file_edit` rewrites files inside the sandbox, `shell_execute` runs in the PRoot environment, `browser_use` drives pages through the accessibility tree, MCP servers connect directly. Every write is snapshotted first, so a bad turn can be rolled back per file or entirely.

## Architecture

| Layer | Responsibility | Entry points |
|:--|:--|:--|
| Presentation | Chat surface, tool cards, settings | `ui/chat`, `ui/settings` |
| Session | Message blocks, streaming render, context trimming, checkpoint and approval hooks | `ChatViewModel` |
| Decision | Agent loop, tool dispatch, loop detection, sub-agent budgets, result governance | `agent/`, `tools/` |
| Execution | Sandbox, shell, terminal, browser, accessibility | `sandbox/`, `terminal/`, `browser/` |
| Foundation | Persistence, model providers, memory, skills, MCP | `data/`, `provider/`, `memory/`, `skills/`, `mcp/` |

## Modules

| Path | Contents |
|:--|:--|
| `ui/chat` | Chat and tool card rendering, hunk-level diffs (`ToolDiffCard`, `DiffPreviewParser`) |
| `tools` | Tool definitions, patch splitting (`PatchSplitter`), checkpoint restore tool |
| `agent` | Loop detection, sub-agent budgets, command risk and approval coordination |
| `sandbox` | PRoot sandbox, shell timeout policy, write checkpoints, output offload |
| `memory` | Fact storage and hybrid retrieval (`HybridMemoryIndex`) |
| `mcp` | MCP gateway and tool registry (prewarm, caps, throttling) |
| `skills` | Skill loading and remote source validation (https, allowlist, hash, no downgrade) |
| `browser` | Accessibility-tree driven control with incremental screen-tree diffs |
| `provider` | Model provider adapters, streaming parsers, OAuth flows |
| `data` | Room persistence, message parts JSON, sessions and settings |
| `speech` | Recording, recognition, playback |
| `observability` | Tool-call event stream (NDJSON) and export |

## Engineering notes

**Result governance**　Output passes through `ToolResultTrim`: head and tail kept, error terms preserved, repeated lines collapsed, oversized content recorded with `omittedChars` and written to disk for retrieval. Read-only results enter an LRU cache (16 entries, 2 MB, 5 minutes) and are not re-executed on a hit.

**Loop detection**　`ToolProgressSignature` normalizes timestamps, UUIDs and progress percentages so noise does not read as progress; `ToolLoopDetector` compares normalized signatures.

**Timeouts**　`ShellTimeoutPolicy` splits a command on `&& || ; |` and takes the widest per-segment timeout, so a long task is not killed by a short budget.

**Approval**　`CommandRiskPolicy` grades commands as safe, warning or blocking. Blocking never bypasses; warnings open an `ApprovalCoordinator` card that offers allow-for-session (six hours) or allow-always, and anything else is a refusal. Sixty seconds of silence counts as refusal, late taps are discarded, and the model receives an actionable "pick a safer approach" message instead of a bare error.

**Checkpoints**　`SessionCheckpoint` records prior content before each write, keeps the earliest version when the same file is written twice, and evicts oldest entries past the file and size limits. `checkpoint_restore` rolls back one file or all of them.

**Retrieval**　`HybridMemoryIndex` is dependency-free Kotlin: CJK text splits into unigrams and bigrams, Latin into words; BM25 (k1=1.2, b=0.75) and cosine over a 128-dim hashed sparse vector are fused at 0.65 / 0.35, and result lists merge with RRF (k=60).

**MCP**　Beyond the `mcp_call` gateway, models can call `mcp__server__tool` directly. The registry prewarms at start, caps 20 tools per server and 48 overall, and throttles refreshes to 120 seconds.

**Remote skills**　https only, host allowlist, SHA-256 in 64 hex characters, 32 MB package limit, upgrades only. `RemoteSourcePolicy` additionally rejects http, localhost, private ranges, credentialed URLs and unusual ports.

**Hunk editing**　An edit is split into hunks, the diff card groups rows by old-line jumps, additions in green and deletions in red, each selectable. Confirming writes only the chosen hunks; the tool card summary is updated and persisted into the message parts JSON, so it survives a restart.

## Build

```bash
# JDK 17 and Android SDK 36 required; NDK for the native bridges
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

CI runs two jobs: `assemble` is the gate and must pass; `unit-tests` runs separately, uploads its report and does not decide the build result. Gradle caches and a mirror absorb dependency flakiness.

## Testing

746 test classes cover tools, sandbox, approvals, checkpoints, indexing, UI parsing and end-to-end flows. Cases needing network, hardware or provider credentials are guarded by `ExternalSourcesRule` and skipped rather than failed; about 214 are skipped today.

The source was stripped of comments in one pass, which damaged native preprocessor directives and test anchors. 68 structural markers were restored, and a comment-insensitive token hash over 2257 files confirmed no code was lost.

## Limitations

1. The artifact is debug-signed and meant for internal testing; distribution requires your own keystore.
2. `HybridMemoryIndex` is a standalone, tested index layer; the default recall path still uses the original implementation.
3. First rootfs extraction is slow, worse on low-end storage.
4. Some vendor ROMs throttle background work and accessibility services; manual grants and battery exemptions are needed.

## Layout

```
app/
├── build.gradle.kts                module config, flavors, tests, mirrors
├── libs/                           vendored dependency (rclone.aar)
└── src/
    ├── main/
    │   ├── java/com/openminis/app/ Kotlin sources, 38 packages
    │   ├── cpp/                    native bridges: PTY, crash handling, tokenizer
    │   ├── assets/                 rootfs, jieba dictionaries, KaTeX fonts, pages
    │   ├── jniLibs/arm64-v8a/      PRoot shared libraries
    │   └── res/                    layouts, icons, strings
    └── test/java/com/openminis/app/ unit tests and baselines
.github/workflows/android.yml       layered CI
gradle/                             version catalog and wrapper
```
