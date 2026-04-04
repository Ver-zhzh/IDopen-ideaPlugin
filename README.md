# IDopen

IDopen 是一个 IntelliJ IDEA 插件项目，目标是在 JetBrains IDE 内提供接近 OpenCode / Claude Code 风格的 coding agent 体验。

当前仓库已经不是模板工程，而是一个可运行、可调试、可继续扩展的插件原型，核心链路包括：

- 多会话对话侧栏
- OpenAI-compatible 模型接入
- ChatGPT 登录态接入
- 工具调用与审批流
- 项目级 commands / agents / skills
- MCP 配置发现、检查与调用
- 会话持久化、todo、step、response parts

## 当前状态

这个项目目前更接近“可演示、可继续工程化推进的原型”，而不是一个只做静态展示的插件壳子。

已经具备的关键能力：

- 在右侧 `IDopen` Tool Window 中进行多轮对话
- 支持多会话创建、切换、持久化恢复
- 支持流式输出、停止当前运行、审批高风险操作
- 支持 OpenAI-compatible provider 与 ChatGPT auth provider
- 支持项目级上下文读取、文件读取、搜索、命令执行、补丁预览
- 支持 slash commands、todo、step 状态与工具进度展示
- 支持 MCP server 的配置发现、检查、资源/工具/提示词访问

## 功能概览

### 1. 对话与会话

- 右侧 `IDopen` 工具窗对话面板
- 多会话创建、切换、删除
- 会话标题自动生成
- 会话 transcript、todo、step 状态持久化
- 流式输出与运行中断
- 对修改文件、执行命令等高风险操作进行审批
- 支持信任模式与无限制模式切换

### 2. Provider 与模型接入

#### OpenAI-compatible

- 配置 `Base URL / API Key / Default Model`
- 支持获取模型列表
- 支持测试连接
- 支持自定义请求头
- 支持工具调用模式切换：`AUTO / ENABLED / DISABLED`

#### ChatGPT Auth

- 支持 ChatGPT 登录态
- 支持访问令牌与刷新令牌管理
- 支持 ChatGPT 配额 / usage 查询
- 支持可用模型列表与默认模型选择

### 3. Agent 工具

当前已实现的主要工具包括：

- `read_project_tree`
- `read_file`
- `search_text`
- `get_current_file`
- `get_current_selection`
- `apply_patch_preview`
- `run_command`
- `todo_read`
- `todo_write`
- `skill`
- `mcp_list_servers`
- `mcp_describe_server`
- `mcp_list_tools`
- `mcp_call_tool`
- `mcp_list_resources`
- `mcp_read_resource`
- `mcp_list_resource_templates`
- `mcp_list_prompts`
- `mcp_get_prompt`

说明：

- `apply_patch_preview` 用于生成并预览文件修改，再由用户确认应用
- `run_command` 仅在项目目录内执行，并带命令安全策略
- `skill` 用于加载项目内的 `SKILL.md`
- MCP 相关工具用于发现和访问外部 MCP server

### 4. 交互增强

- 当前文件 / 当前选区作为附件上下文
- Slash commands 输入提示
- todo 面板与 step 状态显示
- 项目级 commands / agents / skills 自动发现
- MCP 检查面板
- ChatGPT 配额查看

## 内置 Slash Commands

### 本地动作

- `/help`
- `/new`
- `/delete`
- `/settings`
- `/quota`
- `/commands`
- `/agents`
- `/mcp`
- `/skills`
- `/stop`
- `/file on|off`
- `/selection on|off`
- `/trust on|off`
- `/unlimited on|off`

### 内置提示词命令

- `/project`
- `/review`
- `/explain`
- `/plan`
- `/todo`

此外，项目目录中的自定义 Markdown commands 也会被自动注册为 slash commands。

## 项目级扩展约定

IDopen 会从项目目录读取本地扩展定义。

### Project Commands

扫描目录：

- `.opencode/command`
- `.opencode/commands`
- `.claude/commands`

每个 `.md` 文件都可以映射为一个自定义 slash command。

支持的 frontmatter：

- `description`
- `argument-hint`
- `agent`
- `model`

### Project Agents

扫描目录：

- `.opencode/agent`
- `.opencode/agents`
- `.claude/agents`
- `.agents`

每个 `.md` 文件都可以映射为一个项目级 agent。

支持的 frontmatter：

- `description`
- `model`

### Project Skills

扫描目录：

- `.claude/skills`
- `.agents/skills`
- `.opencode/skill`
- `.opencode/skills`

每个 `SKILL.md` 可以被 agent 通过 `skill` 工具按名称加载。

支持的 frontmatter：

- `name`
- `description`

### MCP 配置发现

按顺序扫描：

- `~/.claude.json`
- `~/.claude/.mcp.json`
- `<project>/.mcp.json`
- `<project>/.claude/.mcp.json`

支持：

- 用户级 / 项目级 / 本地级配置合并
- 同名 server 覆盖
- `disabled: true` 禁用已有配置
- `stdio` / `http` / `sse` 类型 server
- `command`、`args`、`url`、`env`、`headers`、`oauth.scopes` 等字段

## 运行时架构

当前 runtime 已经从简单 transcript 逐步推进到更接近 agent session 的结构：

- `round -> step -> parts`
- assistant response parts
- tool invocation state
- approval state
- todo state
- 会话持久化与恢复
- 上下文裁剪与 step group 压缩

相关核心模块：

- [AgentSessionService.kt](/D:/Project/IDopen/IDopen/src/main/kotlin/com/idopen/idopen/agent/AgentSessionService.kt)
- [SessionStepSupport.kt](/D:/Project/IDopen/IDopen/src/main/kotlin/com/idopen/idopen/agent/SessionStepSupport.kt)
- [ContextWindowSupport.kt](/D:/Project/IDopen/IDopen/src/main/kotlin/com/idopen/idopen/agent/ContextWindowSupport.kt)
- [OpenAICompatibleClient.kt](/D:/Project/IDopen/IDopen/src/main/kotlin/com/idopen/idopen/agent/OpenAICompatibleClient.kt)

## 本地化与文本安全回退

这个仓库在多轮迭代中引入过部分历史坏编码文本。为避免这些文本直接暴露给用户，当前已经加入统一的本地化兜底策略。

核心规则：

- 正常中文文案优先显示中文
- 如果中文文案检测为坏编码，自动回退英文
- 设置页、MCP 配置页、工具窗、ChatGPT quota 展示都接入了这套逻辑

相关实现：

- [LocalizedTextSupport.kt](/D:/Project/IDopen/IDopen/src/main/kotlin/com/idopen/idopen/settings/LocalizedTextSupport.kt)
- [ChatGptQuotaSupport.kt](/D:/Project/IDopen/IDopen/src/main/kotlin/com/idopen/idopen/settings/ChatGptQuotaSupport.kt)

## 主要目录结构

```text
IDopen/
|- build.gradle.kts
|- settings.gradle.kts
|- README.md
|- src/
|  |- main/
|  |  |- kotlin/com/idopen/idopen/
|  |  |  |- agent/
|  |  |  |- inspection/
|  |  |  |- settings/
|  |  |  |- toolwindow/
|  |  |- resources/META-INF/
|  |     |- plugin.xml
|  |     |- pluginIcon.svg
|  |- test/
|     |- kotlin/com/idopen/idopen/
|        |- agent/
|        |- settings/
|        |- toolwindow/
|- gradle/
```

## 核心模块说明

### `agent`

负责 agent 主流程和运行时状态：

- `AgentSessionService`：会话生命周期、消息发送、运行状态、审批流
- `AgentSessionStore`：会话持久化
- `IntelliJAgentTools`：向 agent 暴露 IDE / 本地工具
- `ProviderDefinitionSupport` / `ProviderRuntimeSupport`：provider 定义与运行时能力
- `ProviderConfigSupport`：将设置转换为运行配置
- `OpenAICompatibleClient`：OpenAI-compatible 接口调用
- `ChatGptResponsesClient`：ChatGPT responses 协议接入
- `ProjectAgentSupport`：项目级 agents
- `SkillSupport`：项目级 skills
- `McpSupport` / `McpRuntimeSupport` / `McpInspectorSupport`：MCP 发现与运行时接入
- `SessionStepSupport`：step、todo、parts 组织

### `toolwindow`

负责主交互界面：

- `IDopenToolWindowFactory`
- `IDopenToolWindowPanel`
- `SlashCommandSupport`
- `ProjectSlashCommandSupport`

### `settings`

负责设置、provider、认证与本地化兜底：

- `IDopenSettingsState`
- `IDopenSettingsConfigurable`
- `IDopenMcpConfigurable`
- `ChatGptAuthSupport`
- `ChatGptQuotaSupport`
- `LocalizedTextSupport`

### `inspection`

- `IDopenEnvironmentInspector`

## 技术栈

| 组件 | 版本 / 技术 |
|---|---|
| 开发语言 | Kotlin 1.9.25 |
| JVM | 21 |
| 构建工具 | Gradle 8.12.1 |
| IntelliJ Platform Gradle Plugin | 2.3.0 |
| 目标平台 | IntelliJ IDEA Community 2024.2.5 |
| sinceBuild | 242 |
| untilBuild | 251.* |
| JSON 处理 | Jackson Databind 2.18.2 |

## 快速开始

### 环境要求

- JDK 21+
- IntelliJ IDEA 2024.2+

### 构建插件

```bash
./gradlew buildPlugin
```

### 启动调试 IDE

```bash
./gradlew runIde
```

### 运行测试

```bash
./gradlew test
```

### 插件产物

打包结果通常位于：

- `build/distributions/`

## 当前开发重点

当前更适合继续推进的方向：

- 继续增强 patch / edit 执行能力
- 继续收敛 step / parts 渲染与 runtime 协议
- 持续清理历史坏编码文本
- 提高 provider capability 与恢复策略的一致性

## 许可证

当前仓库未单独声明许可证；如需开源发布，请补充明确的 LICENSE 文件。
