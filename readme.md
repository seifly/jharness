# JHarness

**JHarness，是一个基于 Java 的开源 Agent Runtime。**

它参考 **DeepSeek Harness** 的设计理念，核心思想只有一句话：

> **Everything is a Plugin. 一切皆插件。**

JHarness 基于 **Spring Boot 3 + PF4J** 构建，将 Agent 的核心能力拆分为独立、可插拔的插件：

**Model · Tools · Memory · Skills · Workflow · MCP · Message · Scheduler**

模型、工具、记忆、技能、工作流、MCP、消息通道以及定时任务，都可以作为独立插件自由组合、替换和扩展。

开发者可以自由接入不同的大模型，开发自己的 **Tools、Skills、MCP**，组合不同的能力模块，甚至构建属于自己的 **Agent 工作流**。

JHarness 希望解决的，不只是「如何开发一个 Agent」，而是进一步探索：

> **如何用 Java，构建一个真正开放、可扩展、可持续演进的 Agent 基础设施。**

从一个 Agent，到一套 Runtime；
从固定功能，到无限插件；
让 Agent 的每一种能力，都可以被自由扩展。

### JHarness Everything is a Plugin.

[Gitee · JHarness](https://gitee.com/seifly/jharness?utm_source=chatgpt.com)


## 技术栈

| 组件 | 版本 | 说明 |
|------|------|------|
| Java | 17 | 最低版本 |
| Spring Boot | 3.5.11 | 宿主应用框架 |
| PF4J | 3.15.0 | 插件框架核心 |
| Lettuce | 6.7.1.RELEASE | Redis 客户端（SDK 内共享配置存储 `ConfigStore` / 向量检索，支持 Redis 8 Vector Set） |
| OkHttp | 4.12.0 | HTTP 客户端（宿主与多数插件共用） |
| Apache Tika | 2.9.2 | 文档解析（PDF/DOCX/TXT/MD，由 SDK 引入） |
| Jackson | 2.17.2 | JSON 处理 |
| Lombok | - | 编译期注解处理器（provided，不随插件包分发） |
| SLF4J | - | 日志门面（运行时由宿主提供实现） |


## 项目结构

```
jharness/
├── plugin-framework/          # SDK：插件框架核心（共享接口、基类、工具类）
├── plugin-app/                # 宿主应用（Spring Boot 启动入口）
├── plugins/                   # 插件集合
│   ├── models-plugin/         # 模型管理：LlmService 实现（HTTPProvider）与 OpenAI 兼容调用
│   ├── sessions-plugin/       # 会话管理：会话状态与持久化
│   ├── skills-plugin/         # 技能管理：技能注册、加载、搜索、安装
│   ├── memory-plugin/         # 记忆管理：短期/长期记忆存储与演进
│   ├── tools-plugin/          # 工具集成：文件、命令、Web、技能、定时等工具集
│   ├── workflow-plugin/       # 核心引擎：Agent 运行时、上下文构建、协作与工作流编排
│   ├── channel-plugin/        # 通知通道：多平台消息接入与语音转录
│   ├── cron-plugin/           # 定时任务：基于 Quartz 的调度
│   ├── mcp-plugin/            # MCP 协议：Model Context Protocol 客户端
│   ├── evolution-plugin/      # 自我进化：反馈管理与提示词优化
│   ├── heartbeat-plugin/      # 心跳服务：周期性检查与主动行动
│   ├── rag-plugin/            # 知识库：插件化 RAG（VectorStore SPI / Qwen3 Embedding / Redis HNSW 向量检索）
│   ├── ui-plugin/             # Web 控制台：REST 控制器 + 前端页面
│   ├── development-plugin/    # 开发辅助（骨架，控制器已迁至 ui-plugin）
│   ├── hello-plugin/          # 示例插件
│   └── themes-plugin/         # 主题插件（骨架）
```


## 构建

```bash
# 编译全部
mvn clean compile

# 打包宿主应用
mvn clean package -pl plugin-app -am

# 打包单个插件
mvn clean package -pl plugins/models-plugin -am
```

## 运行

```bash
# 启动宿主应用（pom 已把 workingDirectory 切到工程根，plugins/ 相对路径可直接命中）
mvn -pl plugin-app spring-boot:run
```

默认 `spring.pf4j.path=plugins`，直接以各插件子模块的 `target/classes` 作为 classpath 根，无需打包或建软链。另有 `runtime-plugins/` 作为部署期插件目录（当前为空，`.lib/` 为共享依赖占位）。
插件热重载、`.lib/` 共享依赖、`plugin.properties` 编码等开发期细节见 [readme_run.md](readme_run.md)。
## 免责声明

> **请在使用本框架前仔细阅读以下声明。**

- 本项目仅供**学习、研究与技术交流**使用。使用者应遵守所在国家/地区的相关法律法规，不得将本项目用于任何违法、侵权、侵犯他人隐私或危害网络安全的用途。
- 本框架具备调用外部 LLM API、执行本地命令、读写文件、访问网络、接入第三方消息平台（如飞书、微信、Telegram 等）的能力。启用相关插件及通道即表示使用者**自行承担**由此产生的全部风险与责任。
- 本框架按"**现状（AS IS）**"提供，**不提供任何明示或暗示的保证**，包括但不限于适用性、可靠性、安全性、无侵权及无错误的保证。作者与贡献者**不对**因使用或无法使用本框架而导致的任何直接或间接损失（含数据丢失、业务中断、合规风险等）承担责任。
- 使用本框架进行的一切操作（包括但限于文件操作、命令执行、对外发送消息、调用付费 API）均由使用者本人控制与负责。请勿在未授权环境下执行危险操作，妥善保管 API Key、Token 等敏感凭证。
- 本项目可能依赖第三方 SDK、服务与数据。第三方服务的可用性、安全性与合规性由各提供方负责，本项目不对其作出任何承诺。
- 若您不同意上述条款，请**立即停止使用**本框架及其相关代码。
