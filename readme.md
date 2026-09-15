# JHarness

Spring Boot 3 + PF4J 插件化 Agent 框架。

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
│   ├── ui-plugin/             # Web 控制台：REST 控制器 + 前端页面
│   ├── development-plugin/    # 开发辅助（骨架，控制器已迁至 ui-plugin）
│   ├── hello-plugin/          # 示例插件
│   └── themes-plugin/         # 主题插件（骨架）
```

## 技术栈

| 组件 | 版本 | 说明 |
|------|------|------|
| Java | 17 | 最低版本 |
| Spring Boot | 3.5.11 | 宿主应用框架 |
| PF4J | 3.15.0 | 插件框架核心 |
| Jedis | 5.1.0 | Redis 客户端（SDK 内共享配置存储 ConfigStore） |
| Lombok | - | 编译期注解处理器（provided，不随插件包分发） |
| SLF4J | - | 日志门面（运行时由宿主提供实现） |

## 插件依赖列表

### 共享 SDK（plugin-framework）

所有插件共同依赖，由父 POM `plugins/pom.xml` 统一引入：

| 依赖 | scope | 说明 |
|------|-------|------|
| `cn.seifly.jharness:plugin-framework` | compile | SDK：契约接口、配置模型、工具类（见下） |
| `org.pf4j:pf4j` | compile | PF4J 插件框架核心 |
| `org.slf4j:slf4j-api` | compile | 日志门面 |
| `org.projectlombok:lombok` | provided | @Slf4j/@Data 等注解 |
| `org.springframework:spring-beans` | provided | @Autowired 等注解支持 |
| `org.springframework:spring-web` | provided | @RestController 等注解支持 |

SDK 主要内容（`cn.seifly.jharness.plugin.framework`）：

| 包 | 内容 |
|----|------|
| `llm` | `LlmService` / `Message` / `LLMResponse` / `ToolCall` / `ToolDefinition` / `StreamEvent` |
| `tools` | `Tool` / `ToolService` / `TokenUsageService` / `StreamAwareTool` / `ToolException` |
| `memory` | `MemoryService` |
| `service` | `ServiceRegistry` / `Service` / `AgentRuntimeService` / `ChannelService` / `StreamCallback` |
| `bus` | `MessageBus` / `InboundMessage` / `OutboundMessage` |
| `config` | `Config` 及 `ModelsConfig` / `ProvidersConfig` / `ChannelsConfig` / `EvolutionConfig` / `MCPServersConfig` / `ToolsConfig` 等 |
| `redis` | `ConfigStore` / `RedisShared` / `RedisKeys`（跨插件共享配置存储） |
| `security` | `SecurityGuard` / `SecurityService` / `SSLUtils` |
| `web` | `PluginControllerRegistry` / `ApiResult` |
| 根包 | `BaseException` / `StringUtils` / `HostEnv` / 扩展点接口 |

---

### 1. models-plugin

> Model：Agent 怎么思考？`LlmService` 实现（`HTTPProvider`）与 OpenAI 兼容 API 调用。

| 依赖 | scope | 说明 |
|------|-------|------|
| `com.squareup.okhttp3:okhttp:4.12.0` | compile | HTTP 客户端 |
| `com.fasterxml.jackson.core:jackson-databind` | compile | JSON 处理 |

**跨插件依赖：** 无（基础插件）

**服务注册：** 向 `ServiceRegistry` 注册 `LlmService` 实现（`HTTPProvider`，支持 `reload` 热替换凭证）。契约与数据结构（`LlmService`/`Message`/`ToolCall`/`StreamEvent`）已提升至 SDK，其它插件不再 `provided` 依赖本插件。

---

### 2. sessions-plugin

> Session：Agent 当前做到了哪里？会话状态管理与持久化。

| 依赖 | scope | 说明 |
|------|-------|------|
| `com.fasterxml.jackson.core:jackson-databind` | compile | JSON 持久化 |
| `com.fasterxml.jackson.datatype:jackson-datatype-jsr310` | compile | JDK8 时间类型支持 |
| ~~`models-plugin`~~ | ~~provided~~ | 残留：Message 已提升至 SDK，代码不再引用 |

---

### 3. skills-plugin

> Skill：Agent 怎么完成一类任务？技能注册、加载、搜索、安装。

| 依赖 | scope | 说明 |
|------|-------|------|
| `com.squareup.okhttp3:okhttp:4.12.0` | compile | GitHub API 调用 |
| `com.fasterxml.jackson.core:jackson-databind` | compile | registry.json 解析 |

**跨插件依赖：** 无

---

### 4. memory-plugin

> Memory：Agent 能记住什么？短期/长期记忆存储与演进。

| 依赖 | scope | 说明 |
|------|-------|------|
| `com.fasterxml.jackson.core:jackson-databind` | compile | 记忆持久化 |
| `com.fasterxml.jackson.datatype:jackson-datatype-jsr310` | compile | JDK8 时间类型支持 |
| ~~`models-plugin`~~ | ~~provided~~ | 残留：已改用 SDK `LlmService`/`Message`，代码不再引用 |
| ~~`evolution-plugin`~~ | ~~provided~~ | TODO：EvaluationFeedback（待启用） |

**服务注册：** 启动时向 `ServiceRegistry` 注册 `MemoryService` 实现，workflow-plugin 通过契约获取，反向无编译期依赖。

---

### 5. tools-plugin

> Tool：Agent 能做什么？文件操作、命令执行、Web 检索、技能调用、Token 统计等工具集。

| 依赖 | scope | 说明 |
|------|-------|------|
| `com.squareup.okhttp3:okhttp:4.12.0` | compile | WebFetchTool/WebSearchTool/SocialNetworkTool |
| `com.fasterxml.jackson.core:jackson-databind` | compile | JSON 处理 |
| `skills-plugin` | provided | SkillsTool 引用 SkillsLoader/SkillsInstaller/SkillsSearcher 等 |
| `cron-plugin` | provided | CronTool 引用 CronService/CronJob/CronSchedule |
| ~~`models-plugin`~~ | ~~provided~~ | 残留：Tool/ToolDefinition 已提升至 SDK，代码不再引用 |
| ~~`sessions-plugin`~~ | ~~provided~~ | 已移除：SubagentManager 迁至 workflow-plugin |
| ~~`workflow-plugin`~~ | ~~provided~~ | 已移除：MessageBus 已提升至 SDK |
| ~~`mcp-plugin`~~ | ~~provided~~ | 已移除：MCPTool 归属 mcp-plugin |

**运行期依赖声明：** `plugin.dependencies=cron-plugin,skills-plugin`

**服务注册：** 启动时向 `ServiceRegistry` 注册 `ToolService` / `TokenUsageService` 实现。

**说明：** 文件与命令工具的 `SecurityGuard` 已提升至 SDK（`plugin.framework.security`），不再有遗留引用。

---

### 6. workflow-plugin

> Workflow：多个 Agent 怎么协作？核心引擎、上下文构建、协同策略、工作流编排。

| 依赖 | scope | 说明 |
|------|-------|------|
| `com.fasterxml.jackson.core:jackson-databind` | compile | WorkflowGenerator JSON 解析 |
| `io.github.cdimascio:dotenv-java:3.0.0` | compile | ConfigLoader 环境变量加载 |
| `sessions-plugin` | provided | SessionManager/ToolCallRecord |
| `tools-plugin` | provided | ExecTool/SkillsTool/WebFetchTool 等工具实现类 |
| `skills-plugin` | provided | SkillsLoader/SkillInfo（系统提示词技能摘要） |
| `evolution-plugin` | provided | FeedbackManager/PromptOptimizer（已启用） |
| `channel-plugin` | provided | Channel/ChannelManager（已启用） |
| `mcp-plugin` | provided | MCPServersConfig（运行期需其类加载器父链） |
| ~~`models-plugin`~~ | ~~provided~~ | 残留：配置类已提升至 SDK `framework.config`，代码不再引用 |
| ~~`memory-plugin`~~ | ~~provided~~ | 已解除：改用 SDK `MemoryService` 契约 |

**运行期依赖声明：** `plugin.dependencies=evolution-plugin,models-plugin,mcp-plugin,channel-plugin,sessions-plugin,skills-plugin,tools-plugin`

**服务注册：** 向 `ServiceRegistry` 注册 `AgentRuntimeService`，并从注册表获取 `LlmService` / `ToolService` / `MemoryService` 实现，避免反向编译期依赖。

---

### 7. channel-plugin

> Integration：Agent 怎么连接外部世界？多平台消息通道与语音转录。

| 依赖 | scope | 说明 |
|------|-------|------|
| `com.squareup.okhttp3:okhttp:4.12.0` | compile | 各通道 HTTP 通信、AliyunTranscriber 语音转录 |
| `com.fasterxml.jackson.core:jackson-databind` | compile | BaseChannel/WebhookServer/转录等 JSON 处理 |
| `com.fasterxml.jackson.datatype:jackson-datatype-jsr310` | compile | WechatChannel ObjectMapper 时间类型 |
| `com.larksuite.oapi:oapi-sdk:2.5.3` | compile | 飞书 SDK（FeishuChannel） |
| `io.github.lith0924:wechat-ilink-sdk:2.2.2` | compile | 微信 iLink SDK（WechatChannel） |
| `com.google.zxing:core:3.5.3` | compile | 登录二维码生成 |
| `com.google.zxing:javase:3.5.3` | compile | 二维码图像编码 |
| ~~`models-plugin`~~ | ~~provided~~ | 残留：已改用 SDK `LlmService`，代码不再引用 |
| ~~`workflow-plugin`~~ | ~~provided~~ | 已解除：MessageBus/Inbound/OutboundMessage 已提升至 SDK（`framework.bus`） |
| ~~config~~ | - | 已解决：`ChannelsConfig` 已提升至 SDK（`framework.config`） |

**支持通道：** Telegram、Discord、飞书、钉钉、QQ、企业微信、微信、WhatsApp、MaixCam

**注意：** 飞书 SDK、微信 iLink SDK、ZXing 同时需在宿主 `plugin-app` 中声明（开发期由宿主 ClassLoader 暴露给插件）。

---

### 8. development-plugin

> Development：开发辅助（骨架）。原 Web REST API 控制器已迁移至 `ui-plugin`。

| 依赖 | scope | 说明 |
|------|-------|------|
| 无 | - | 仅保留 Plugin 骨架与 `plugin.properties`，无业务依赖 |

---

### 9. ui-plugin

> UI：人怎么和 Agent 交互？Web 控制台（REST 控制器 + 前端页面）。

| 依赖 | scope | 说明 |
|------|-------|------|
| `com.fasterxml.jackson.core:jackson-databind` | compile | WebUtils 与各控制器 JSON 序列化 |
| `org.springframework:spring-webmvc` | provided | ChatController 使用 `SseEmitter` |
| `skills-plugin` | provided | SkillsController 引用 SkillsLoader/SkillInfo |

**运行期依赖声明：** `plugin.dependencies=skills-plugin`

**控制器：** Auth、Channels、Chat（SSE 流式）、Config、Cron、Feedback、Files、Health、MCP、Models、Providers、Sessions、Skills、TokenStats、Upload、WebResource、Workspace

**配置读取：** 通过 SDK `RedisShared.store()` 获取 `ConfigStore` 从 Redis 读取各插件配置，不直接注入跨插件对象。

---

### 10. cron-plugin

> Cron：定时调度。基于 Quartz 的 cron 任务管理。

| 依赖 | scope | 说明 |
|------|-------|------|
| `org.quartz-scheduler:quartz:2.3.2` | compile | 定时任务调度框架 |
| `com.cronutils:cron-utils:9.1.6` | compile | cron 表达式解析与下次执行时间计算 |
| `com.fasterxml.jackson.core:jackson-databind` | compile | CronStore 持久化 |
| `com.fasterxml.jackson.datatype:jackson-datatype-jsr310` | compile | JDK8 时间类型支持 |
| ~~config~~ | - | 已解决：配置统一走 SDK `Config` |

---

### 11. mcp-plugin

> MCP：Model Context Protocol 客户端。支持 SSE/Stdio/StreamableHttp 三种传输。

| 依赖 | scope | 说明 |
|------|-------|------|
| `com.squareup.okhttp3:okhttp:4.12.0` | compile | HTTP / StreamableHttp 通信 |
| `com.squareup.okhttp3:okhttp-sse:4.12.0` | compile | SSE 事件解析 |
| `com.fasterxml.jackson.core:jackson-databind` | compile | JSON 处理 |
| ~~`tools-plugin`~~ | ~~provided~~ | 已解除：改用 SDK `ToolService` 契约注册 MCPTool |
| ~~config~~ | - | 已解决：`MCPServersConfig` 已提升至 SDK |

---

### 12. evolution-plugin

> Evolution：Agent 自我进化。反馈管理、提示词优化、变体管理。

| 依赖 | scope | 说明 |
|------|-------|------|
| `com.squareup.okhttp3:okhttp:4.12.0` | compile | PromptOptimizer 调用 LLM |
| `com.fasterxml.jackson.core:jackson-databind` | compile | JSON 处理 |
| `com.fasterxml.jackson.datatype:jackson-datatype-jsr310` | compile | FeedbackManager/VariantManager 持久化 |
| ~~`models-plugin`~~ | ~~provided~~ | 残留：已改用 SDK `LlmService`/`Message`，代码不再引用 |
| ~~config~~ | - | 已解决：`EvolutionConfig` 已提升至 SDK |

---

### 13. heartbeat-plugin

> Heartbeat：Agent 心跳服务。周期性检查与主动行动。

| 依赖 | scope | 说明 |
|------|-------|------|
| ~~`models-plugin`~~ | ~~provided~~ | 残留：`HeartbeatService` 当前无任何跨插件引用 |
| ~~config~~ | - | 已解决：配置统一走 SDK `Config` |

---

## 跨插件依赖关系图

**运行期依赖**（`plugin.properties` 的 `plugin.dependencies`，决定加载顺序与类加载器父链）：

```
    workflow-plugin ──► evolution-plugin, models-plugin, mcp-plugin,
                        channel-plugin, sessions-plugin, skills-plugin, tools-plugin
    tools-plugin    ──► cron-plugin, skills-plugin
    ui-plugin       ──► skills-plugin
```

**编译期依赖**（Maven `provided`，其中多数已随契约提升退化为残留）：

```
    sessions / memory / tools / channel / evolution / heartbeat / workflow  ──► models-plugin（残留，代码已无引用）
    tools-plugin    ──► skills-plugin、cron-plugin（有效）
    workflow-plugin ──► sessions / tools / skills / evolution / channel / mcp（有效）
    ui-plugin       ──► skills-plugin（有效）
```

**独立 / 骨架：** hello-plugin、heartbeat-plugin、cron-plugin、mcp-plugin 无跨插件运行期依赖；development-plugin、themes-plugin 为骨架。

## PF4J 类加载器注意事项

PF4J 每个插件拥有独立的类加载器，跨插件共享类必须走以下机制：

1. **SDK 提升（首选）**：跨插件复用的数据结构与契约（`Message`、`LlmService`、`ToolService`、`MemoryService`、`MessageBus`、`Config`、`SecurityGuard` 等）统一放在 `plugin-framework`，由宿主 ClassLoader 加载，天然全局唯一。
2. **运行期依赖声明**：确需引用另一插件的实现类时，除 Maven `provided` 依赖外，**必须**在 `plugin.properties` 声明 `plugin.dependencies=<id>`。`SpringPluginManager#loadPlugins()` 按依赖做拓扑排序（Kahn）后依次加载，`DefaultPluginManager#createPluginClassLoader` 才会把被依赖插件的类加载器挂到父链上；缺少该声明时运行期抛 `NoClassDefFoundError`。
3. **契约解耦（替代双向依赖）**：工具、记忆、Token 统计、模型调用等能力优先提升为 SDK 契约，由提供方在 `ServiceRegistry` 注册实现、消费方按契约获取，避免插件间互相 `provided` 依赖。
4. **第三方依赖**：通过 `maven-shade-plugin` 打进插件 fat-jar，并排除 `cn/seifly/jharness/plugin/framework/**`（否则插件内接口副本与宿主不是同一 Class，PF4J `isAssignableFrom` 校验失败、扩展无法被发现）。开发期源码运行时，公共重依赖由宿主 `plugin-app` 声明暴露，插件私有依赖放 `plugins/.lib/`。
5. **Spring 注解**（`@Autowired`、`@RestController`）由宿主 `provided`，运行时由 Spring 容器注入。

## 待后续统一调整项

| 项 | 说明 | 影响范围 |
|----|------|---------|
| ~~config 分散~~ | ✅ 已完成：`Config` 及各子配置已提升至 SDK `framework.config` | 所有引用 config 的插件 |
| ~~共享数据结构提升~~ | ✅ 已完成：`Message`/`LlmService`/`ToolCall` 等已提升至 SDK | models/sessions/tools/workflow 等 |
| ~~SecurityGuard 引用~~ | ✅ 已完成：security 已提升至 SDK `framework.security` | tools-plugin |
| ~~TODO 依赖启用~~ | ✅ 已完成：workflow 的 evolution/channel、tools 的 cron 均已启用 | workflow、tools |
| 插件契约化解耦 | tools/memory/mcp 经 `ServiceRegistry` 暴露能力，替代双向编译期依赖 | workflow/tools/mcp/memory |
| 残留依赖清理 | 各插件 pom 中已不再使用的 `models-plugin` 等 `provided` 依赖需删除 | sessions/memory/tools/channel/evolution/heartbeat |
| 骨架插件处置 | development-plugin 控制器已迁空、themes-plugin 尚未实现 | development、themes |

## 不迁移模块

| 模块 | 原因 |
|------|------|
| ~~`security/`~~ | 原定不迁移，实际已提升至 SDK（`framework.security`），本条作废 |
| `logger/` | 替换为 SLF4J，原模块不迁移 |
| `cli/dev/` | 不迁移 |
| `qq-gateway` | 不迁移 |

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

默认 `spring.pf4j.path=plugins`，直接以各插件子模块的 `target/classes` 作为 classpath 根，无需打包或建软链。
插件热重载、`.lib/` 共享依赖、`plugin.properties` 编码等开发期细节见 [readme_run.md](readme_run.md)。
