# IDopen

IntelliJ IDEA 插件 - 在 IDE 内部嵌入的编码助手 (Coding Agent)

## 项目简介

IDopen 是一个 IntelliJ IDEA 插件项目，旨在将类似 OpenCode 的编码助手直接集成到 IDE 中。它提供了一个右侧工具窗口，允许开发者在不离开 IDE 的情况下与 AI 编码助手进行对话，执行代码分析、修复错误、生成代码等操作。

## 功能特性

### 核心功能
- **AI 对话工具窗口**: 右侧边栏中的交互式对话框，支持与 AI 助手实时对话
- **OpenAI 兼容 API**: 支持任何兼容 OpenAI 格式的 AI 服务 (如 OpenAI、DeepSeek、通义千问等)
- **流式响应**: 实时显示 AI 回复，无需等待完整响应
- **工具调用 (Tool Calling)**: 支持 AI 调用 IDE 内置工具执行实际操作
- **上下文感知**: 可附加当前文件、选中代码等上下文信息
- **环境检查**: 自动检测并报告插件运行环境状态

### 附加功能
- **多模型支持**: 可配置和切换不同的 AI 模型
- **自定义 HTTP 头**: 支持添加自定义请求头
- **命令超时控制**: 可配置工具执行的超时时间
- **设置持久化**: 所有配置项自动保存

## 技术栈

| 组件 | 版本/技术 |
|------|----------|
| 开发语言 | Kotlin 1.9.25 |
| JVM 版本 | 21 |
| 构建工具 | Gradle |
| IntelliJ 平台 | IC 2024.2.5 (兼容 242-251.*) |
| JSON 处理 | Jackson 2.18.2 |

## 项目结构

```
IDopen/
├── build.gradle.kts              # Gradle 构建配置
├── settings.gradle.kts           # 项目名称配置
├── gradle.properties             # Gradle 属性
├── gradlew / gradlew.bat         # Gradle 包装器
│
├── src/
│   ├── main/
│   │   ├── kotlin/com/idopen/idopen/
│   │   │   ├── agent/            # 编码代理核心模块
│   │   │   │   ├── AgentModels.kt              # 数据模型定义
│   │   │   │   ├── AgentSessionService.kt      # 会话管理服务
│   │   │   │   ├── AttachmentPromptSupport.kt  # 附件提示支持
│   │   │   │   ├── IntelliJAgentTools.kt       # IntelliJ 专用工具
│   │   │   │   ├── OpenAICompatibleClient.kt   # OpenAI 兼容客户端
│   │   │   │   ├── ProviderConfigSupport.kt    # 提供商配置支持
│   │   │   │   └── ReadWindowSupport.kt        # 读取窗口支持
│   │   │   ├── inspection/         # 检查器模块
│   │   │   │   └── IDopenEnvironmentInspector.kt
│   │   │   ├── settings/           # 设置模块
│   │   │   │   ├── IDopenSettingsConfigurable.kt
│   │   │   │   └── IDopenSettingsState.kt
│   │   │   └── toolwindow/         # 工具窗口模块
│   │   │       ├── IDopenToolWindowFactory.kt
│   │   │       └── IDopenToolWindowPanel.kt
│   │   └── resources/META-INF/
│   │       ├── plugin.xml          # 插件配置描述符
│   │       └── pluginIcon.svg      # 插件图标
│   │
│   └── test/
│       └── kotlin/com/idopen/idopen/agent/
│           ├── AttachmentPromptSupportTest.kt
│           ├── ProviderConfigSupportTest.kt
│           └── ReadWindowSupportTest.kt
│
└── README.md                       # 项目说明文档
```

## 模块说明

### agent (代理模块)
核心功能模块，实现与 AI 服务的交互：
- `OpenAICompatibleClient`: 处理与 OpenAI 兼容 API 的 HTTP 通信
- `AgentSessionService`: 管理 AI 会话状态和生命周期
- `IntelliJAgentTools`: 提供 IDE 专用工具 (如读取文件、执行命令等)
- `AttachmentPromptSupport`: 处理代码上下文的附加和提示

### toolwindow (工具窗口模块)
提供用户界面：
- `IDopenToolWindowFactory`: 工具窗口工厂
- `IDopenToolWindowPanel`: 主面板，包含对话框、状态栏、输入区等

### settings (设置模块)
管理插件配置：
- `IDopenSettingsState`: 持久化设置状态
- `IDopenSettingsConfigurable`: 设置界面

### inspection (检查器模块)
- `IDopenEnvironmentInspector`: 环境检查，确保插件正常运行

## 快速开始

### 前置要求
- JDK 21+
- IntelliJ IDEA 2024.2 或更高版本

### 构建项目
```bash
./gradlew buildPlugin
```

### 运行插件
```bash
./gradlew runIde
```

### 配置说明

插件安装后，在 `Settings > IDopen` 中配置：
- **Base URL**: AI 服务地址 (如 `https://api.openai.com/v1`)
- **API Key**: 服务访问密钥
- **Model**: 使用的模型名称
- **Shell Path**: 本地命令执行的 Shell 路径

## 开发指南

### 添加新的 Agent 工具
在 `IntelliJAgentTools.kt` 中定义新的工具函数。

### 修改工具窗口 UI
编辑 `IDopenToolWindowPanel.kt` 文件。

### 添加新的设置项
在 `IDopenSettingsState.kt` 中添加属性，并在 `IDopenSettingsConfigurable.kt` 中添加对应的 UI。

## 兼容性

- **IntelliJ 平台**: 2024.2 - 2025.1 (sinceBuild: 242, untilBuild: 251.*)
- **支持的 IDE**: IntelliJ IDEA, PyCharm, WebStorm, GoLand 等 JetBrains IDE

## 许可证

请查看项目中的 LICENSE 文件。

## 贡献

欢迎提交 Issue 和 Pull Request！

## 相关链接

- [IntelliJ Platform SDK](https://plugins.jetbrains.com/docs/intellij/welcome.html)
- [OpenAI API 文档](https://platform.openai.com/docs)
- [Gradle IntelliJ Plugin](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html)
