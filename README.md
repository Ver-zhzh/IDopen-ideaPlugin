# IDopen

IntelliJ IDEA 插件：把一个可执行的 Coding Agent直接放进 JetBrains IDE。

## 项目简介

IDopen 是一个基于 IntelliJ Platform 开发的 IDE 插件，目标是在编辑器内部提供类似 OpenCode / Claude Code 风格的编码助手体验。插件当前已经具备以下核心能力：

- 在 IDE右侧工具窗口中进行多轮对话
- 接入 OpenAI兼容接口，以及 ChatGPT 登录态
- 支持流式回复、工具调用、审批确认
- 支持会话持久化、todo 面板、步骤状态展示
- 支持 slash commands、项目级 commands、项目级 agents 与 skills
- 支持读取当前文件 / 当前选区等 IDE 上下文
- 支持 MCP（Model Context Protocol）配置发现、检查与运行时调用

这个仓库当前已经不是单纯的模板，而是一个 **可运行、可继续扩展的 IntelliJ 插件原型**，重点覆盖了 Agent 主链路、IDE 工具桥接、项目级扩展机制和基础交互体验。

## 当前能力

###1. 对话与会话
-右侧 `IDopen` Tool Window 对话面板
- 多会话创建、切换、删除
- 会话标题自动生成
- 会话历史、本地 transcript、todo、step 状态持久化
- 流式输出与运行中断
- 审批确认流：对修改、命令执行等高风险动作进行确认

###2. 模型与 Provider
- **OpenAI Compatible** 提供商
 - 可配置 Base URL / API Key / Default Model
 - 支持维护已知模型列表
 - 支持自定义请求头
 - 支持连接测试与模型拉取
- **ChatGPT Auth** 提供商
 - 支持 ChatGPT 登录态 / OAuth令牌管理
 - 自动管理接口地址与认证信息
 - 支持 quota / usage 状态查看
 - 支持受支持模型列表与默认模型选择
- 工具调用模式支持：`AUTO / ENABLED / DISABLED`
- 支持 trust mode / unlimited usage 等运行策略开关

###3. Agent 工具能力
当前插件内已经实现了一批可由 Agent 调用的 IDE / 本地工具，包括：

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

其中：
- `apply_patch_preview` 用于预览并应用文件修改
- `run_command` 用于在项目目录内执行 shell 命令
- `skill` 用于按名称加载项目内的专业技能说明
- MCP相关工具用于检查配置、连接外部 MCP server 并调用其工具 /资源 / prompt
- 涉及变更或执行风险的操作支持审批确认

###4.交互增强
- 当前文件 / 当前选区上下文附加
- slash commands 自动提示与解析
- todo 面板与步骤执行状态可视化
- 项目级 command / agent / skill 探测与切换
- MCP server 检查面板
- ChatGPT 配额查看

## 内置 Slash Commands

插件支持输入 `/`触发命令建议，当前内置命令包括：

### 本地动作命令
- `/help`：显示可用命令
- `/new`：新建会话
- `/delete`：删除当前会话
- `/settings`：打开插件设置
- `/quota`：查看 ChatGPT额度
- `/commands`：查看项目自定义命令
- `/agents`：查看项目 agents
- `/mcp`：查看 MCP 服务
- `/skills`：查看本地 skills
- `/stop`：停止当前运行
- `/file on|off`：切换当前文件上下文
- `/selection on|off`：切换当前选区上下文
- `/trust on|off`：切换信任模式
- `/unlimited on|off`：切换无限制使用

### 内置提示词命令
- `/project`：生成项目分析提示词
- `/review`：生成代码审查提示词
- `/explain`：生成解释提示词
- `/plan`：生成先规划后执行提示词
- `/todo`：生成先维护 todo 再执行提示词

此外，项目目录中的自定义 Markdown commands也会自动注册为 slash commands。

## 项目级扩展约定

IDopen 支持从项目目录读取本地扩展定义。

###1. Project Commands
会扫描以下目录中的 Markdown 文件：

- `.opencode/command`
- `.opencode/commands`
- `.claude/commands`

每个 `.md` 文件都可以被映射成一个自定义 slash command。

支持的 frontmatter 字段包括：
- `description`
- `argument-hint`
- `agent`
- `model`

###2. Project Agents
会扫描以下目录中的 Markdown 文件：

- `.opencode/agent`
- `.opencode/agents`
- `.claude/agents`
- `.agents`

每个 `.md` 文件都可以被映射成一个项目级 agent。

支持的 frontmatter 字段包括：
- `description`
- `model`

###3. Project Skills
会扫描以下目录中的 `SKILL.md` 文件：

- `.claude/skills`
- `.agents/skills`
- `.opencode/skill`
- `.opencode/skills`

skill 可被 Agent通过 `skill` 工具按名称加载，用于注入特定领域的工作流说明与约束。

支持的 frontmatter 字段包括：
- `name`
- `description`

###4. MCP 配置发现
当前会按顺序扫描以下配置文件：

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

## 技术栈

|组件 |版本 / 技术 |
|---|---|
| 开发语言 | Kotlin1.9.25 |
| JVM |21 |
| 构建工具 | Gradle8.12.1 |
| IntelliJ Platform Gradle Plugin |2.3.0 |
|目标平台 | IntelliJ IDEA Community2024.2.5 |
|兼容 sinceBuild |242 |
| JSON处理 | Jackson Databind2.18.2 |

## 项目结构

```text
IDopen-ideaPlugin/
├── build.gradle.kts
├── README.md
├── src/
│ ├── main/
│ │ ├── kotlin/com/idopen/idopen/
│ │ │ ├── agent/ # Agent 核心：会话、工具、Provider、MCP、skills、持久化
│ │ │ ├── inspection/ # 环境检查
│ │ │ ├── settings/ # 设置项、认证与配置界面
│ │ │ └── toolwindow/ # Tool Window UI、slash commands、项目命令发现
│ │ └── resources/META-INF/
│ │ ├── plugin.xml
│ │ └── pluginIcon.svg
│ └── test/
│ └── kotlin/com/idopen/idopen/
│ ├── agent/
│ ├── settings/
│ └── toolwindow/
└── gradle/
```

## 核心模块说明

### `agent`
负责 Agent 主流程和能力执行，包含：

- `AgentSessionService`：会话生命周期、消息发送、运行状态、审批流
- `AgentSessionStore`：会话持久化
- `IntelliJAgentTools`：向 Agent 暴露 IDE / 本地工具
- `ProviderDefinitionSupport` / `ProviderRuntimeSupport`：Provider 定义与运行时选择
- `ProviderConfigSupport`：将设置项转换为 Provider运行配置
- `OpenAICompatibleClient`：与 OpenAI兼容接口交互
- `ChatGptResponsesClient`：与 ChatGPT Responses 协议交互
- `ProjectAgentSupport`：加载项目级 agents
- `SkillSupport`：加载项目级 skills
- `McpSupport` / `McpRuntimeSupport` / `McpInspectorSupport`：MCP 配置发现、运行时对接与检查
- `SessionStepSupport`：step / todo组织与展示支撑

### `toolwindow`
负责用户交互界面，包含：

- `IDopenToolWindowFactory`：工具窗口注册入口
- `IDopenToolWindowPanel`：主面板、会话 UI、输入区、状态区
- `SlashCommandSupport`：内置 slash commands 定义、提示、解析与 prompt 构造
- `ProjectSlashCommandSupport`：项目级 commands发现与解析

### `settings`
负责插件设置与 Provider 配置，包含：

- `IDopenSettingsState`：持久化设置状态
- `IDopenSettingsConfigurable`：设置页 UI
- `ChatGptAuthSupport`：ChatGPT 登录态与认证支持
- `ChatGptQuotaSupport`：ChatGPT 配额相关能力

### `inspection`
- `IDopenEnvironmentInspector`：环境检查与运行状态诊断

## 快速开始

### 环境要求
- JDK21+
- IntelliJ IDEA2024.2+

### 构建插件
```bash
./gradlew buildPlugin
```

### 启动调试 IDE
```bash
./gradlew runIde
```

构建产物通常位于：

```text
build/distributions/IDopen-1.0-SNAPSHOT.zip
```

## 使用方式

###1. 安装并启动插件
运行 `runIde` 后，会启动一个带沙箱环境的 IntelliJ IDEA。

###2. 打开设置
进入：

```text
Settings / Preferences -> IDopen
```

###3. 配置 Provider
常见配置项包括：

- `Display Language`
- `Provider Type`
 - `OPENAI_COMPATIBLE`
 - `CHATGPT_AUTH`
- `Base URL`
- `API Key`
- `Default Model`
- `Known Models`
- `Tool Calling Mode`
- `Shell Path`
- `Command Timeout Seconds`
- `Custom Headers`
- `Trust Mode`
- `Unlimited Usage`

###4. 开始对话
打开右侧 `IDopen` 工具窗口，即可开始：

- 普通自然语言提问
- 携带当前文件 /选区上下文提问
- 使用 `/project`、`/review`、`/todo` 等命令快速生成提示词
- 查看项目 commands / agents / skills / MCP 配置

## 开发说明

### 添加新的 Agent 工具
在 `src/main/kotlin/com/idopen/idopen/agent/IntelliJAgentTools.kt` 中：

1. 在 `definitions()` 中注册工具描述和输入 schema
2. 在 `execute()` 中分发工具调用
3. 实现工具逻辑，并处理必要的审批、读写与错误返回

### 添加新的 Slash Command
可在以下位置扩展：

- 内置命令：`toolwindow/SlashCommandSupport.kt`
- 项目级命令：在项目目录下新增 `.md` 文件到约定目录

### 添加新的项目 Agent / Skill
可直接在项目目录中新增约定文件：

- agents：放入 agent目录中的 `.md`
- skills：放入 skill目录中的 `SKILL.md`

### 添加新的设置项
需要同步修改：

- `IDopenSettingsState.kt`
- `IDopenSettingsConfigurable.kt`

### UI入口
Tool Window 主界面位于：

- `src/main/kotlin/com/idopen/idopen/toolwindow/IDopenToolWindowPanel.kt`

##兼容性说明

- 插件基于 IntelliJ Platform `242` 系列开始兼容
- 当前 Gradle 配置目标平台为 `IC2024.2.5`
- `untilBuild` 当前未显式限制，实际兼容性仍建议结合 JetBrains 平台 API变动进行验证

## 已知现状

当前仓库已经实现了较完整的 Agent 主链路；不过从发布角度看，仍有一些信息可以继续完善，例如：

- `plugin.xml` 中 vendor / 插件描述仍偏占位
- README仍可继续补充截图、安装方式、发行说明
- Marketplace 发布所需元信息还可以进一步完善

##许可证

请查看仓库中的 LICENSE 文件。

##参考链接

- [IntelliJ Platform SDK](https://plugins.jetbrains.com/docs/intellij/welcome.html)
- [IntelliJ Platform Gradle Plugin2.x](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html)
- [OpenAI API Docs](https://platform.openai.com/docs)
- [Model Context Protocol](https://modelcontextprotocol.io/)
