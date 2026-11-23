# Minis Agent（Android）源码

本目录是一个完整的 Android 工程（Kotlin + Jetpack Compose + Material 3），
包名 `com.openminis.app`，版本 `1.61-ubuntu24.04-minis-plugins`（versionCode 91）。

应用品牌为原版 **Minis**（M 字母图标 + Minis 名字）。内置的 DeepSeek / WorkBuddy /
Qoder / MiniMax Code 等是模型服务供应商，与应用品牌无关，保留原名。

## 版本沿革

- **1.61（本版）**：统一插件生态。新增 `PluginEcosystem`（原生能力插件 / Skill /
  MCP 三类统一模型）、`McpPluginCatalog`（6 个可一键安装的 MCP 插件模板）、
  `McpToolCatalog`（MCP 工具解析 / 冲突消解 / 歧义消解）。设置界面 MCP 集成页新增
  "Plugins" 精选 Tab，选中即预填标准 JSON 走既有导入流程安装。另把 `ToolResultTrim`
  接入 shell 结果展示、file_edit 接入 diff 预览，MCP 引导加入工具名冲突提示。
  全量测试基建加 `forkEvery` 分批，消除长跑 JVM 崩溃。
- **1.60**：全维度技术增强，新增 6 个经充分单元测试的纯逻辑能力模块
  （共 48 个新测试），覆盖代码能力、省 token、记忆进化、并行调度、屏幕操控、
  MCP 插件生态、长任务与时间预算。详见下文"1.60 增强模块"。另修复全量测试
  基建：为单测 JVM 配置稳定堆与 JIT 参数，消除超大图解码的环境假失败与 JVM 崩溃。
- **1.59**：品牌回退 Minis（M 图标 + 名字），模型供应商名保留；工具/token 缓存。
- **1.58**：工具 schema / token 估算缓存；结构化事实记忆 `memory_fact`
  （`subject · predicate = value`，重复强化、冲突按置信度与时效消解，跨会话注入）。

## 1.60 增强模块（均位于 app/src/main，配套测试位于 app/src/test）

| 模块 | 维度 | 能力 |
|---|---|---|
| `tools/ChangePreview.kt` | 代码能力 | 有界 LCS 行级 diff + 变更摘要，确定性、可截断、超大输入诚实标注 |
| `tools/ToolResultTrim.kt` | 省 token | 结构性裁剪工具结果（保头尾、保错误行、标注省略），能力不掉 |
| `tools/BatchToolOrder.waves()` | 核心循环 | 依赖波次计算：无依赖调用同波并行，读在写后，减少同步栅栏 |
| `data/repository/MemoryFactRepository.recall()` | 记忆进化 | 混合检索（任意词 + 字段加权 + 半衰期时间衰减），兼容原 API |
| `tools/McpToolCatalog.kt` | 插件生态 | MCP 工具解析、跨服务器命名冲突检测、紧凑能力清单、歧义消解 |
| `accessibility/ScreenTreeDiff.kt` | 屏幕操控 | UI 树增量差异（稳定签名、重复 id 安全），只回传变化、省 token |
| `agent/CheckpointLedger.kt` | 长任务 | 幂等阶段检查点、断点续跑摘要、JSONL 持久化 |
| `agent/DeadlineBudget.kt` | 时间预算 | 共享总期限 + 每步子预算 + 紧迫度分级提示，防止长任务无限拖延 |

> 说明：这些模块为纯逻辑、无外部依赖，随 APK 一并编译交付，源码与测试完整。
> 接入 Agent 运行路径时无需改动数据结构；本版优先保证既有高度成熟的
> 工具注册/分发链零回归，故采取"模块 + 测试"的安全增量方式。

## 构建

1. 用 Android Studio（推荐 Ladybug 及以上）打开本目录，或使用命令行。
2. 在工程根目录创建 `local.properties`，写入本机 Android SDK 路径：

   ```
   sdk.dir=/你的/Android/Sdk
   ```

3. 构建交付用 APK（github 分发渠道，debug 签名，可直接安装）：

   ```
   ./gradlew assembleGithubRelease --max-workers=2 --no-daemon
   ```

   产物：`app/build/outputs/apk/github/release/app-github-release.apk`

4. 运行单元测试：

   ```
   ./gradlew testGithubReleaseUnitTest --max-workers=2 --no-daemon
   ```

## 目录结构

- `app/src/main/java/com/openminis/app/` —— 主源码（Agent 主循环、Provider 接入、
  shell/浏览器/无障碍工具、沙箱、上下文工程、UI 等，777 个 Kotlin 文件）。
- `app/src/main/res/` —— 资源（含多语言 values / values-zh / values-zh-rTW）。
- `app/src/main/assets/` —— 内置资产（含 Ubuntu 24.04 rootfs 引导脚本等）。
- `app/src/test/` —— 单元测试（722 个 Kotlin 文件）。
- `app/src/androidTest/` —— 仪器化测试。
- `app/libs/rclone.aar` —— 内置依赖。
- `app/schemas/` —— Room 数据库迁移 schema。
- `app/provider-customization.properties.example` —— 构建期定制项模板（OAuth
  标识符等，默认留空；如需 Claude OAuth 登录请按模板填写）。

## 说明

- 发布构建使用 debug 签名密钥（无需额外 keystore），github 与 store 两个
  分发渠道共用同一 applicationId 与签名，可互相覆盖安装。
- 内置的 Ubuntu 24.04 环境通过 PRoot 在应用沙箱内运行，提供 bash / python3 /
  apt 等真实 Linux 工具链。
- 本包不包含 `build/`、`.gradle/`、`.kotlin/`、`.cxx/` 等可再生产物及构建日志；
  `local.properties` 未附带（含打包机本机路径），请按上文创建。
