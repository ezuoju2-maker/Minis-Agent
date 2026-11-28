<div align="center">

# Minis Agent

**在手机上直接干活的 AI Agent**<br>
*A local-first AI agent that runs on Android*

![version](https://img.shields.io/badge/version-2.0--final-1f6feb?style=flat-square)
![platform](https://img.shields.io/badge/Android-8.0%2B-3ddc84?style=flat-square&logo=android&logoColor=white)
![kotlin](https://img.shields.io/badge/Kotlin-2.1.0-7f52ff?style=flat-square&logo=kotlin&logoColor=white)
![compose](https://img.shields.io/badge/Compose-Material%203-4285f4?style=flat-square)
![build](https://img.shields.io/badge/build-passing-2ea043?style=flat-square)

<sub>2297 个文件 · 815 份主源码 · 746 个测试类 · 30 个工具 · 38 个功能包</sub>

[中文](#中文) · [English](#english)

</div>

---

## 中文

### 这是什么

一个把工具执行放在设备上而不是云端的 Android Agent。模型只负责决定下一步做什么，文件读写、命令执行、网页操作、记忆检索都在手机本地完成，命令跑在 Ubuntu 24.04 的 PRoot 沙箱里，命令在 PRoot 沙箱里真实执行，不经过任何云端执行环境。

源码 2297 个文件，不含任何构建产物；包名 `com.openminis.app`，versionCode 92，最终提交 `b6424dd`。

### 能做什么

| 能力 | 实际行为 | 落点 |
|---|---|---|
| 改代码 | 一次编辑拆成若干 hunk，详情页逐段预览，勾掉哪段哪段就不生效，写盘后摘要回写消息记录 | `tools/patch/PatchSplitter.kt`<br>`ui/chat/ToolDiffCard.kt` |
| 跑命令 | 按 `&&`、`\|\|`、`;`、`\|` 拆段，逐段取最宽超时；危险命令先弹卡片，拒了就把「换更安全的做法」回给模型 | `sandbox/ShellTimeoutPolicy.kt`<br>`agent/approval/*` |
| 操作浏览器 | 基于可访问性树定位元素，只把变化的节点算进 diff，坐标和选择器两条路都能走 | `accessibility/ScreenTreeDiff.kt` |
| 无头浏览器 | 沙箱内跑 Lightpanda（CDP），可导航、读取正文、点击、输入、执行 JS、截图；首次使用下载 74 MB 并校验 SHA-256 | `browser/lightpanda/*` |
| 长期记忆 | 中文按单双字 bigram、英文按整词切分，BM25 与 128 维稀疏向量余弦按 0.65/0.35 融合，多路结果用 RRF 合并 | `memory/retrieval/HybridMemoryIndex.kt` |
| 接 MCP | 统一网关 `mcp_call`，也可直接点名 `mcp__server__tool`；启动预热，单服务 20 个、全局 48 个工具封顶 | `tools/McpNativeTools.kt`<br>`tools/McpToolRegistry.kt` |
| 拆子任务 | 委派给子代理执行，默认 60 轮，上下限 8–200，倒数 `max(3, cap/5)` 轮开始提醒收尾 | `agent/SubAgentBudgetPolicy.kt` |
| 可回滚 | 每次写文件前留旧内容，能按单文件或整轮回滚，模型直接调 `checkpoint_restore` | `sandbox/checkpoint/SessionCheckpoint.kt` |
| 装技能 | 远程技能只认 https、主机白名单、64 位十六进制 SHA-256，版本号降级直接拒绝 | `skills/remote/*` |
| 长记性 | 自我改进笔记本：你说「不对 / 应该是 / 以后都」时，模型把教训压成一句祈使句存盘；下次遇到同类任务自动召回注入，不复述、不打扰。重复确认提置信度，重复条目自动合并，说「忘掉」即可删除 | `agent/learning/*`<br>`tools/LessonTool.kt` |
| 找技能 | 接任务就自己去装：`find_skills` 检索 Anthropic Skills、Superpowers、ECC、Composio、WorkBuddy / OpenClaw 社区包等公开技能库（数千份 SKILL.md），按名字/路径/描述打分；索引 24 小时重爬，并且每周用 GitHub 搜索自己发现新技能仓库，不依赖任何写死的清单。源清单 `skills-sources.json` 只是起点 | `skills/radar/*`<br>`tools/FindSkillsTool.kt` |
| 看过程 | 每次工具调用写一行 NDJSON（序号、轮次、耗时、结果），设置页一键导出分享 | `observability/TraceLog.kt` |
| 省重复 | 只读工具结果进 LRU 缓存，16 条 / 2 MB / 5 分钟后失效 | `tools/ToolResultCache.kt` |
| 长任务不跑偏 | 会话目标随每次调用注入、压缩后仍在；历史可按关键词回查原文片段 | `agent/goal/SessionGoals.kt`<br>`agent/continuity/HistorySearch.kt` |

### 架构

```
ui/chat          ChatViewModel 会话编排 · 消息块 · 流式渲染 · 工具卡片 · 上下文裁剪
   │
agent/           循环控制 · 循环检测 · 子代理预算 · 审批协调
   │
tools/           工具实现 · 结果裁剪 · 结果缓存 · MCP 网关 · hunk 拆分
   │
sandbox/         PRoot 会话 · 超时策略 · 检查点 · 大结果落盘
   │
data · provider · memory · skills · observability · speech
                 Room 持久化 · 模型接入 · 检索索引 · 技能源 · 事件流 · 语音
```

一次工具调用的真实链路：模型给出调用 → `executeToolCached` 查缓存 → 命中就返回 → 未命中进 `executeTool` → 危险命令过审批 → 进沙箱执行 → 结果经 `ToolResultTrim` 裁剪 → 超限内容落盘 → 写事件流 → 回填消息块。

### 几个值得说的实现

**循环检测不吃噪声。** `ToolProgressSignature` 先把时间戳、UUID、百分比进度归一成占位符，再比对，避免「同一步被当成新步骤」；`ToolLoopDetector` 只对归一后的签名计数。

**审批有三档，最低那档绕不过去。** `SAFE` 直接执行，`WARN` 弹卡片，`BLOCK` 无论用户点几次都不放行；指纹归一的做法是去引号、压空白、数字串一律归零，所以 `rm -rf /tmp/a` 和 `rm -rf /tmp/b` 算同一条命令。会话内记忆 6 小时，`ALLOW_ALWAYS` 不过期。

**裁剪不是砍尾巴。** 超长输出保留头尾、优先保住中文错误词、折叠连续重复行，超出部分记 `omittedChars` 后落盘，模型要细节可以再取，不靠猜。

**结果持久化到消息里。** 按段重选后，摘要不只改内存里的气泡，还写回该条消息的 `parts_json`，重启、切会话再回来都对得上。

### 构建

```bash
# JDK 17 + Android SDK 36 + NDK（编译 native 桥接）
./gradlew :app:assembleDebug

adb install -r app/build/outputs/apk/debug/app-debug.apk
```

CI 分两层：`assemble` 是硬门禁，`unit-tests` 独立跑、单独上传报告，不决定构建成败；Gradle 缓存与阿里云镜像用来扛依赖下载抖动。

### 质量与边界

- 测试 746 个类，覆盖工具、沙箱、审批、检查点、索引、UI 解析与端到端流程；依赖网络、设备或供应商凭据的用例由 `ExternalSourcesRule` 守卫，条件不满足时跳过（当前约 214 个）。
- 去注释那轮曾误删 native 预处理指令和测试锚点，已修复 68 处结构标记，并用「非注释 token 去空白哈希」对 2257 个文件全量比对，确认代码没丢。
- 发布包是 debug 签名，只适合自测；对外分发请用自有 keystore 重签。
- `HybridMemoryIndex` 已独立可测，默认召回路径还没切过去。
- 首次解压 rootfs 有明显等待；部分厂商 ROM 会限制后台进程与无障碍服务，需手动放行。

### 目录

```
app/
├─ build.gradle.kts                模块配置、flavor、测试与镜像
├─ libs/rclone.aar                 预置依赖
└─ src/
   ├─ main/java/com/openminis/app/ 主源码（38 个包）
   ├─ main/cpp/                    PTY、崩溃处理、分词桥接
   ├─ main/assets/                 rootfs、结巴词典、KaTeX 字体、前端页面
   ├─ main/jniLibs/arm64-v8a/      PRoot 动态库
   ├─ main/res/                    布局、图标、文案
   └─ test/java/com/openminis/app/ 单元测试与黄金基准
.github/workflows/android.yml      分层 CI
gradle/                            版本目录与 wrapper
```

### 成品

| | |
|---|---|
| 安装包 | `Minis-Agent-2.0-final.apk` |
| 大小 | 102,524,665 字节 |
| SHA-256 | `b3fc2293bc4a8b4ce6e77e20304f4d96662b91603540568702a4b2339e37d768` |
| 来源 | 提交 `b6424dd` 的 CI 构建，`assemble` 与 `unit-tests` 均通过 |

> 本文件所在提交只加了这份说明，没动代码；二进制仍对应 `b6424dd`。

---

## English

### What it is

An Android agent that executes on the device instead of in the cloud. The model only decides what to do next; file access, command execution, page automation and memory retrieval all happen locally, with commands running inside an Ubuntu 24.04 PRoot sandbox and no cloud execution environment involved.

2297 files of source, no build output in the tree. Package `com.openminis.app`, versionCode 92, final commit `b6424dd`.

### What it does

| Capability | Behaviour | Where |
|---|---|---|
| Edit code | One edit is split into hunks, previewed per section, unchecked hunks never land; the summary is written back to the message record | `tools/patch/PatchSplitter.kt`<br>`ui/chat/ToolDiffCard.kt` |
| Run commands | Segments split on `&&`, `\|\|`, `;`, `\|` each get the widest timeout; risky commands raise a card, a refusal sends "pick a safer route" back to the model | `sandbox/ShellTimeoutPolicy.kt`<br>`agent/approval/*` |
| Drive a browser | Elements located through the accessibility tree, only changed nodes enter the diff, coordinates and selectors both work | `accessibility/ScreenTreeDiff.kt` |
| Headless browser | Lightpanda over CDP inside the sandbox: navigate, read text, click, type, run JS, capture screenshots; first use downloads 74 MB and verifies SHA-256 | `browser/lightpanda/*` |
| Remember facts | CJK split into unigrams and bigrams, Latin into words; BM25 fused with cosine over a 128-dim sparse vector at 0.65/0.35, lists merged with RRF | `memory/retrieval/HybridMemoryIndex.kt` |
| Speak MCP | One gateway `mcp_call`, or call `mcp__server__tool` directly; prewarm on start, capped at 20 tools per server and 48 overall | `tools/McpNativeTools.kt`<br>`tools/McpToolRegistry.kt` |
| Split work | Delegated sub-agents run 60 turns by default, floor 8 ceiling 200, warnings from `max(3, cap/5)` turns out | `agent/SubAgentBudgetPolicy.kt` |
| Roll back | Prior file content is kept before every write; rollback by file or by whole run, the model calls `checkpoint_restore` | `sandbox/checkpoint/SessionCheckpoint.kt` |
| Install skills | Remote skills need https, an allowlisted host and a 64-hex SHA-256; version downgrades are refused | `skills/remote/*` |
| Learn from you | A self-improving notebook: when you correct it ("no, put the report in out/", "from now on…"), it compresses the lesson into one imperative rule and stores it; similar later tasks recall it automatically and it applies the rule silently. Repeats raise confidence, duplicates merge, and "forget" deletes an entry | `agent/learning/*`<br>`tools/LessonTool.kt` |
| Find skills | Self-serve on every task: `find_skills` searches Anthropic Skills, Superpowers, ECC, Composio, WorkBuddy / OpenClaw community packs and thousands of other SKILL.md repos, ranks by name/path/description, then installs; the index re-crawls every 24h and the radar discovers new repos itself through GitHub search every week — no hardcoded catalogue. `skills-sources.json` is only a starting point | `skills/radar/*`<br>`tools/FindSkillsTool.kt` |
| Watch it run | Every tool call appends an NDJSON line (seq, turn, duration, outcome), exportable from settings | `observability/TraceLog.kt` |
| Avoid repeats | Read-only results enter an LRU cache, 16 entries / 2 MB / 5 minutes | `tools/ToolResultCache.kt` |
| Stay on long tasks | A session goal is injected into every call and survives compaction; history stays searchable by keyword with excerpts | `agent/goal/SessionGoals.kt`<br>`agent/continuity/HistorySearch.kt` |

### Architecture

```
ui/chat          ChatViewModel orchestration · message blocks · streaming · tool cards · context trim
   │
agent/           loop control · loop detection · sub-agent budget · approval coordination
   │
tools/           tool implementations · result trim · result cache · MCP gateway · hunk splitting
   │
sandbox/         PRoot session · timeout policy · checkpoints · large-result offload
   │
data · provider · memory · skills · observability · speech
                 Room persistence · model access · retrieval index · skill sources · event stream · speech
```

The path of one tool call: model emits a call → `executeToolCached` checks the cache → a hit returns immediately → a miss enters `executeTool` → risky commands pass approval → execution inside the sandbox → result trimmed by `ToolResultTrim` → oversized parts offloaded → event written → message block updated.

### Implementation notes

**Loop detection ignores noise.** `ToolProgressSignature` replaces timestamps, UUIDs and progress percentages with placeholders before comparing, so one step is never mistaken for a new one; `ToolLoopDetector` counts only normalized signatures.

**Three approval tiers, the lowest cannot be argued with.** `SAFE` runs, `WARN` raises a card, `BLOCK` never proceeds regardless of taps. Fingerprints strip quotes, collapse whitespace and zero out digit runs, so `rm -rf /tmp/a` and `rm -rf /tmp/b` count as the same command. Session memory lasts six hours; `ALLOW_ALWAYS` does not expire.

**Trimming keeps both ends.** Long output retains head and tail, protects Chinese error terms, collapses repeated lines, and records `omittedChars` for the offloaded remainder so the model can fetch detail instead of guessing.

**Summaries live in the message.** After a hunk re-selection, the summary is written back into that message's `parts_json`, not just the in-memory bubble, so it survives restarts and session switches.

### Build

```bash
# JDK 17, Android SDK 36 and the NDK for native bridges
./gradlew :app:assembleDebug

adb install -r app/build/outputs/apk/debug/app-debug.apk
```

CI runs two layers: `assemble` is the hard gate, `unit-tests` runs separately and uploads its report without deciding the build result. Gradle caches plus an Aliyun mirror absorb dependency flakiness.

### Quality and limits

- 746 test classes cover tools, sandbox, approvals, checkpoints, indexing, UI parsing and end-to-end flows; cases needing networks, devices or provider credentials are guarded by `ExternalSourcesRule` and skipped rather than failed (~214 today).
- The comment-strip pass once removed native preprocessor directives and test anchors; 68 markers were restored and a whitespace-insensitive token hash over 2257 files confirmed nothing was lost.
- The published APK uses a debug signature and suits internal testing only; re-sign with your own keystore before distribution.
- `HybridMemoryIndex` is standalone and tested; the default recall path does not use it yet.
- The first rootfs extraction is slow, and some vendor ROMs throttle background processes and accessibility services.

### Layout

```
app/
├─ build.gradle.kts                module config, flavors, tests, mirrors
├─ libs/rclone.aar                 vendored dependency
└─ src/
   ├─ main/java/com/openminis/app/ sources (38 packages)
   ├─ main/cpp/                   PTY, crash handling, tokenizer bridge
   ├─ main/assets/                rootfs, jieba dictionaries, KaTeX fonts, web pages
   ├─ main/jniLibs/arm64-v8a/     PRoot shared objects
   ├─ main/res/                   layouts, icons, strings
   └─ test/java/com/openminis/app/ unit tests and golden baselines
.github/workflows/android.yml      layered CI
gradle/                            version catalog and wrapper
```

### Release

| | |
|---|---|
| Artifact | `Minis-Agent-2.0-final.apk` |
| Size | 102,524,665 bytes |
| SHA-256 | `b3fc2293bc4a8b4ce6e77e20304f4d96662b91603540568702a4b2339e37d768` |
| Built from | commit `b6424dd`, CI `assemble` and `unit-tests` both green |

> This file was added in a docs-only commit; no code changed, so the binary still matches `b6424dd`.
