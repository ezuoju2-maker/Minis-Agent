# Minis Agent 2.0 · 最终版

> 中文在前，English follows.

---

# 一、中文

## 1. 项目简介

Minis Agent 2.0 是一个运行在 Android 上的本地 AI Agent 应用。核心思路是「模型负责决策，设备负责执行」：Agent 在手机上直接读写文件、执行 Shell、操作浏览器、调用 MCP 服务、维护长期记忆，并在本地沙箱（Ubuntu 24.04 rootfs + PRoot）里跑完整工具链，不依赖云端执行环境。

本仓库是最终版的完整源码，无裁剪、无占位、无 TODO 遗留（唯一一处 `TODO` 字样出现在停用词表的字符串常量中）。

## 2. 版本与构建信息

| 项目 | 值 |
|---|---|
| 应用包名 | `com.openminis.app` |
| versionCode | `92` |
| versionName | `2.0-final-ubuntu24.04-minis` |
| compileSdk / targetSdk / minSdk | 36 / 35 / 26 |
| 语言与 UI | Kotlin 2.1.0、Jetpack Compose、Material 3 |
| 构建工具 | Gradle 8.11.1、AGP 8.7.3、JDK 17 |
| 最终提交 | `b6424dd` |
| 发布产物 | `Minis-Agent-2.0-final.apk`（102,524,665 字节） |
| 产物 SHA-256 | `b3fc2293bc4a8b4ce6e77e20304f4d96662b91603540568702a4b2339e37d768` |

> APK 由提交 `b6424dd` 的 CI 构建产出（`assemble` 与 `unit-tests` 均通过）。本次文档提交只增加说明文件，不改动任何代码或资源，因此二进制与源码仍一一对应。

## 3. 核心能力

| 能力 | 说明 |
|---|---|
| 文件与补丁 | 读、写、编辑、整段应用补丁；编辑结果按 hunk 分段展示，可勾选后局部重写 |
| Shell 执行 | 命令分段超时预算、危险命令识别与审批、输出超长自动裁剪并落盘可回取 |
| 浏览器控制 | 基于可访问性树的页面操作，屏幕树增量 diff，支持坐标与选择器双寻址 |
| 本地沙箱 | Ubuntu 24.04 rootfs + PRoot，会话级文件系统映射，命令在其中真实执行 |
| 记忆系统 | 事实写入/读取 + 混合检索索引（BM25 与向量分数融合、RRF 合并） |
| MCP 接入 | 原生 `mcp_call` 网关、`mcp__server__tool` 直呼、工具清单预热与节流 |
| 子代理 | 委派执行、轮次预算（默认 60，边界 8–200）、进度回传 |
| 检查点 | 写入前记录旧内容，支持按文件或全量回滚，`checkpoint_restore` 工具直达 |
| 审批机制 | 风险分级 SAFE/WARN/BLOCK，会话内记忆 6 小时，BLOCK 永不可绕过 |
| 技能系统 | 本地技能包加载，远程源仅允许 https + 主机白名单 + SHA-256 校验，禁止降级 |
| 可观测 | 工具调用事件流（NDJSON），设置页可一键导出分享 |
| 结果缓存 | 只读工具结果 LRU 缓存（16 条 / 2 MB / 5 分钟） |

## 4. 架构总览

```
┌──────────────────────────────────────────────────────────────┐
│  表现层  ui/chat · ui/settings · Compose + Material 3        │
├──────────────────────────────────────────────────────────────┤
│  会话层  ChatViewModel  ·  消息块 / 流式渲染 / 工具卡片       │
│          上下文裁剪 · 检查点挂载 · 审批挂起与恢复             │
├──────────────────────────────────────────────────────────────┤
│  决策层  Agent 循环  ·  工具调度  ·  循环检测  ·  子代理预算  │
│          结果裁剪  ·  结果缓存  ·  事件打点                   │
├──────────────────────────────────────────────────────────────┤
│  执行层  tools/  ·  sandbox/  ·  terminal/  ·  browser/      │
│          PRoot 沙箱  ·  Shell 超时策略  ·  风险策略           │
├──────────────────────────────────────────────────────────────┤
│  支撑层  data/  ·  provider/  ·  memory/  ·  skills/  ·  mcp/ │
│          Room 持久化  ·  模型供应商  ·  检索索引  ·  远程源    │
└──────────────────────────────────────────────────────────────┘
```

## 5. 模块地图

| 包路径 | 职责 | 关键实现 |
|---|---|---|
| `ui/chat` | 会话界面、消息块、工具卡片、diff 渲染 | `ChatViewModel`、`ToolDiffCard`、`DiffPreviewParser` |
| `tools` | 工具定义与执行 | `AgentTools`、`FileEditTool`、`PatchSplitter`、`CheckpointRestoreTool` |
| `agent` | 循环控制、预算、审批 | `ToolLoopDetector`、`SubAgentBudgetPolicy`、`approval/*` |
| `sandbox` | 沙箱与资源治理 | `ShellTimeoutPolicy`、`checkpoint/SessionCheckpoint`、`offload/*` |
| `memory` | 记忆存储与检索 | `HybridMemoryIndex`、`MemoryFactRepository` |
| `mcp` | MCP 客户端与工具注册 | `McpNativeTools`、`McpToolRegistry` |
| `skills` | 技能加载与远程更新 | `SkillRegistry`、`RemoteSourcePolicy` |
| `observability` | 事件流与追踪 | `TraceLog` |
| `browser` | 浏览器自动化 | 可访问性树驱动、屏幕树 diff |
| `provider` | 模型供应商接入 | 多协议适配、流式解析 |
| `data` | 持久化 | Room 实体、`ChatRepository`、消息 parts JSON |
| `speech` | 语音识别与合成 | 录音、识别、朗读 |

## 6. 关键子系统说明

**工具结果治理**
执行结果先经 `ToolResultTrim` 处理：保留首尾上下文，中文错误词优先保留，连续重复行折叠，超限内容记录 `omittedChars` 后经 `ContextOffload` 落盘，模型可按需回取。裁剪后的结果进入 `ToolResultCache`，只读工具命中缓存不再重复执行。

**循环与预算**
`ToolProgressSignature` 归一化时间戳、UUID、进度百分比，避免同一动作因噪声被判为新动作；`ToolLoopDetector` 基于归一化签名检测重复循环。子代理受 `SubAgentBudgetPolicy` 约束，默认 60 轮，最低 8、最高 200，并在上限前 `max(3, cap/5)` 轮开始告警。

**危险命令审批**
`CommandRiskPolicy` 识别删除、覆盖关键路径、`find -exec rm`、`xargs rm` 等模式并分级；`CommandApprovalPolicy` 负责指纹归一（去引号、压空白、数字串归零）与会话记忆；`ApprovalCoordinator` 以挂起式卡片向用户求确认，60 秒超时自动拒绝，迟到点击一律作废，拒绝时回传「不要重试，请换更安全的替代方案」的指令文案。

**检查点与回滚**
`SessionCheckpoint` 在每次写文件前记录旧内容（首次写入保留最早版本），按文件数与字节数上限淘汰最旧记录，单文件内容超限截断并标记 `truncated`。模型可调用 `checkpoint_restore` 做单文件或全量回滚。

**记忆检索**
`HybridMemoryIndex` 为纯 Kotlin 零依赖实现：中文按单字与双字 bigram 切分，拉丁按整词；BM25（k1=1.2、b=0.75）与 128 维稀疏哈希向量余弦相似度（FNV-1a 64 位）按 0.65 / 0.35 融合，多路结果用 RRF（k=60）合并。

**MCP 接入**
`McpNativeTools` 提供统一网关，模型既可用 `mcp_call` 指定服务与工具，也可直接用 `mcp__server__tool` 命名调用；`McpToolRegistry` 负责启动预热、单服务 20 个 / 全局 48 个工具上限、120 秒刷新节流。

**远程技能**
`SkillRegistry` 强制 https、校验 id 格式、主机白名单、SHA-256（64 位十六进制）、32 MB 上限，并按版本号拒绝降级；`RemoteSourcePolicy` 额外拒绝 http、localhost、私网字面量、带凭据的 URL 与异常端口。

**UI 分段编辑**
`PatchSplitter` 把一次编辑拆成 hunk，`DiffPreviewParser` 按旧行号跳变分组，`ToolDiffCard` 渲染绿增红删并支持勾选；`applyHunkSelection` 只写回选中段，同时更新工具卡摘要，并把摘要写回消息 parts JSON，重启后仍可见。

## 7. 构建与运行

```bash
# 环境：JDK 17、Android SDK 36、NDK（构建 native 桥接）
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

CI 采用分层策略：`assemble` 为必过门禁，`unit-tests` 独立任务运行并上传报告，不阻塞构建结论；Gradle 缓存与阿里云镜像用于规避依赖下载抖动。

## 8. 测试与质量

- 测试文件 746 个，覆盖工具、沙箱、审批、检查点、索引、UI 解析与端到端流程。
- 依赖外部环境（网络、设备、供应商凭据）的用例由 `ExternalSourcesRule` 守卫，缺失条件时跳过而非失败，当前跳过约 214 个。
- 去注释过程中曾误删 native 文件预处理指令与测试锚点，已修复 68 处结构标记，并以「非注释 token 去空白哈希」对 2257 个文件做全量比对确认无代码丢失。

## 9. 已知限制

1. 发布用 debug 签名，仅适合自测与内部试用，上架或对外分发需改用自有 keystore 重签。
2. `HybridMemoryIndex` 目前是独立可测的索引层，默认召回路径尚未切换到混合检索。
3. 沙箱性能受低端设备存储速度限制，首次解压 rootfs 有明显等待。
4. 部分厂商 ROM 对后台进程与可访问性服务限制较严，需手动授权并关闭电池优化。

## 10. 仓库结构

```
app/
├── build.gradle.kts          # 模块构建配置、flavor、测试与镜像设置
├── libs/                     # 预置依赖（rclone.aar）
└── src/
    ├── main/
    │   ├── java/com/openminis/app/   # 全部 Kotlin 源码（38 个功能包）
    │   ├── cpp/                      # native 桥接（PTY、崩溃处理、分词）
    │   ├── assets/                   # rootfs、结巴词典、KaTeX 字体、页面资源
    │   ├── jniLibs/arm64-v8a/        # PRoot 动态库
    │   └── res/                      # 布局、图标、字符串
    └── test/java/com/openminis/app/  # 单元测试与黄金基准
.github/workflows/android.yml         # 分层 CI
gradle/                               # 版本目录与 wrapper
```

---

# 二、English

## 1. Overview

Minis Agent 2.0 is a local-first AI agent that runs on Android. The model decides, the device executes: the agent reads and writes files, runs shell commands, drives a browser, calls MCP servers and keeps long-term memory, all inside a local Ubuntu 24.04 sandbox (rootfs + PRoot) instead of a cloud runtime.

This repository holds the complete final source: no trimming, no placeholders, no leftover TODOs (the only `TODO` string lives in a stop-word constant).

## 2. Version and build

| Item | Value |
|---|---|
| Application ID | `com.openminis.app` |
| versionCode | `92` |
| versionName | `2.0-final-ubuntu24.04-minis` |
| compileSdk / targetSdk / minSdk | 36 / 35 / 26 |
| Language and UI | Kotlin 2.1.0, Jetpack Compose, Material 3 |
| Toolchain | Gradle 8.11.1, AGP 8.7.3, JDK 17 |
| Final commit | `b6424dd` |
| Release artifact | `Minis-Agent-2.0-final.apk` (102,524,665 bytes) |
| Artifact SHA-256 | `b3fc2293bc4a8b4ce6e77e20304f4d96662b91603540568702a4b2339e37d768` |

The APK is produced by CI from commit `b6424dd` (`assemble` and `unit-tests` both pass). This documentation commit adds text files only and touches no code or resource, so source and binary remain in sync.

## 3. Capabilities

| Capability | Description |
|---|---|
| Files and patches | Read, write, edit and apply patches; edits are split into hunks that can be reviewed and selectively rewritten |
| Shell | Per-segment timeout budget, risky-command detection and approval, oversized output trimmed and offloaded for later retrieval |
| Browser control | Accessibility-tree driven actions with incremental screen-tree diffs, by coordinate or selector |
| Local sandbox | Ubuntu 24.04 rootfs + PRoot with per-session filesystem mapping |
| Memory | Fact storage plus a hybrid retrieval index fusing BM25 with vector scores and RRF |
| MCP | Native `mcp_call` gateway, `mcp__server__tool` direct calls, tool-list prewarm and throttling |
| Sub-agents | Delegated runs with turn budgets (default 60, range 8–200) and progress reporting |
| Checkpoints | Pre-write snapshots with per-file or full rollback through `checkpoint_restore` |
| Approval | Risk levels SAFE/WARN/BLOCK, six-hour session memory, BLOCK can never be bypassed |
| Skills | Local skill loading; remote sources require https, host allowlist, SHA-256 and forbid downgrades |
| Observability | Tool-call event stream in NDJSON, exportable from settings |
| Result cache | Read-only LRU cache (16 entries / 2 MB / 5 minutes) |

## 4. Architecture

```
Presentation   ui/chat · ui/settings · Compose + Material 3
Session        ChatViewModel · message blocks · streaming · tool cards
               context trimming · checkpoint hooks · approval suspend/resume
Decision       agent loop · tool dispatch · loop detection · sub-agent budget
               result trimming · result cache · event tracing
Execution      tools/ · sandbox/ · terminal/ · browser/
               PRoot sandbox · shell timeout policy · risk policy
Foundation     data/ · provider/ · memory/ · skills/ · mcp/
               Room persistence · model providers · retrieval index · remote sources
```

## 5. Module map

| Package | Responsibility | Key files |
|---|---|---|
| `ui/chat` | Chat UI, message blocks, tool cards, diff rendering | `ChatViewModel`, `ToolDiffCard`, `DiffPreviewParser` |
| `tools` | Tool definitions and execution | `AgentTools`, `FileEditTool`, `PatchSplitter`, `CheckpointRestoreTool` |
| `agent` | Loop control, budgets, approvals | `ToolLoopDetector`, `SubAgentBudgetPolicy`, `approval/*` |
| `sandbox` | Sandbox and resource governance | `ShellTimeoutPolicy`, `checkpoint/SessionCheckpoint`, `offload/*` |
| `memory` | Memory storage and retrieval | `HybridMemoryIndex`, `MemoryFactRepository` |
| `mcp` | MCP client and tool registry | `McpNativeTools`, `McpToolRegistry` |
| `skills` | Skill loading and remote updates | `SkillRegistry`, `RemoteSourcePolicy` |
| `observability` | Event stream and tracing | `TraceLog` |
| `browser` | Browser automation | Accessibility-tree driver, screen-tree diff |
| `provider` | Model providers | Multi-protocol adapters, streaming parsers |
| `data` | Persistence | Room entities, `ChatRepository`, message parts JSON |
| `speech` | Speech input and output | Recording, recognition, playback |

## 6. Subsystems

**Tool result governance**
Results pass through `ToolResultTrim`: head and tail are kept, Chinese error terms are preserved first, consecutive duplicate lines are collapsed, and oversized content is recorded with `omittedChars` then offloaded by `ContextOffload` for retrieval. Trimmed results enter `ToolResultCache`, so read-only tools stop re-executing.

**Loop detection and budgets**
`ToolProgressSignature` normalizes timestamps, UUIDs and progress percentages so the same action is not mistaken for a new one; `ToolLoopDetector` detects repetition on normalized signatures. Sub-agents are bounded by `SubAgentBudgetPolicy` (default 60 turns, floor 8, ceiling 200) with warnings starting `max(3, cap/5)` turns before the cap.

**Risky command approval**
`CommandRiskPolicy` classifies deletions, critical-path overwrites, `find -exec rm` and `xargs rm`; `CommandApprovalPolicy` normalizes fingerprints (quotes stripped, whitespace collapsed, digits zeroed) and remembers decisions per session; `ApprovalCoordinator` suspends on a user card, denies after 60 seconds, discards late taps and returns actionable "do not retry, choose a safer alternative" guidance on refusal.

**Checkpoints and rollback**
`SessionCheckpoint` records prior content before each write (earliest version wins on repeated writes), evicts oldest entries past file and byte limits, and marks truncated files. The model can call `checkpoint_restore` for single-file or full rollback.

**Memory retrieval**
`HybridMemoryIndex` is pure Kotlin with no dependencies: CJK text splits into unigram and bigram terms, Latin into words; BM25 (k1=1.2, b=0.75) and cosine similarity over a 128-dim sparse hashed vector (FNV-1a 64) are fused at 0.65 / 0.35, and result lists merge with RRF (k=60).

**MCP**
`McpNativeTools` exposes one gateway: models either pass server and tool to `mcp_call` or call `mcp__server__tool` directly. `McpToolRegistry` prewarms on start, caps 20 tools per server and 48 overall, and throttles refreshes to 120 seconds.

**Remote skills**
`SkillRegistry` enforces https, id format, host allowlist, SHA-256 in 64 hex characters, a 32 MB limit and rejects downgrades; `RemoteSourcePolicy` additionally blocks http, localhost, private literals, embedded credentials and unusual ports.

**Hunk-level editing**
`PatchSplitter` splits an edit into hunks, `DiffPreviewParser` groups rows by old-line jumps, and `ToolDiffCard` renders green additions and red deletions with checkboxes. `applyHunkSelection` writes only selected hunks, updates the tool card summary and persists that summary into the message parts JSON so it survives a restart.

## 7. Build and run

```bash
# Requires JDK 17, Android SDK 36 and the NDK for native bridges
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

CI is layered: `assemble` is the mandatory gate, while `unit-tests` runs independently, uploads its report and does not decide the build conclusion. Gradle caches and an Aliyun mirror reduce dependency flakiness.

## 8. Testing

- 746 test classes span tools, sandbox, approvals, checkpoints, indexing, UI parsing and end-to-end flows.
- Cases that need external networks, devices or provider credentials are guarded by `ExternalSourcesRule` and skipped rather than failed; roughly 214 are skipped today.
- During the comment-strip pass, native preprocessor directives and test anchors were lost; 68 structural markers were restored and a whitespace-insensitive token hash comparison over 2257 files confirmed no code loss.

## 9. Known limitations

1. The published artifact uses a debug signature and suits internal testing only; use your own keystore for distribution.
2. `HybridMemoryIndex` is a standalone, fully tested index layer; the default recall path does not switch to hybrid retrieval yet.
3. Sandbox performance depends on device storage; the first rootfs extraction is noticeably slow.
4. Some vendor ROMs restrict background processes and accessibility services; manual grants and battery-optimization exclusions are required.

## 10. Repository layout

```
app/
├── build.gradle.kts          # module config, flavors, tests, mirrors
├── libs/                     # vendored dependency (rclone.aar)
└── src/
    ├── main/
    │   ├── java/com/openminis/app/   # all Kotlin sources (38 packages)
    │   ├── cpp/                      # native bridges (PTY, crash handling, tokenizer)
    │   ├── assets/                   # rootfs, jieba dictionaries, KaTeX fonts, pages
    │   ├── jniLibs/arm64-v8a/        # PRoot shared libraries
    │   └── res/                      # layouts, icons, strings
    └── test/java/com/openminis/app/  # unit tests and golden baselines
.github/workflows/android.yml         # layered CI
gradle/                               # version catalog and wrapper
```
