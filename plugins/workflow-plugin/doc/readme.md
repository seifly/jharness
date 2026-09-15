# `POST /api/chat/stream` 运行流程

> SSE（Server-Sent Events）流式聊天端点，从 HTTP 请求到 LLM token 回传的完整调用链与各插件参与顺序。

---

## 1. 端点定义

- **入口**：`POST /api/chat/stream`（`Content-Type: application/json`，`Accept: text/event-stream`）
- **请求体**：
  ```json
  {
    "message": "用户消息文本",
    "sessionId": "web:<uuid>",
    "images": ["/path/to/image.png"]
  }
  ```
- **响应**：`SseEmitter`（超时 5 分钟），逐 token 推送 `data:` 事件，结束发送 `data: [DONE]`

---

## 2. 插件启动顺序（依赖拓扑）

`SpringPluginManager.loadPlugins()` 按 `plugin.properties` 中的 `plugin.dependencies` 做拓扑排序，保证依赖插件先加载。`/api/chat/stream` 链路涉及的插件启动顺序如下：

| 顺序 | 插件 | 提供的关键能力 | 本链路作用 |
|------|------|----------------|-----------|
| 1 | `memory-plugin` | `MemoryService` | 长期记忆读写（记忆上下文注入） |
| 2 | `sessions-plugin` | `SessionManager` | 会话历史、摘要、工具调用记录持久化 |
| 3 | `skills-plugin` | `SkillsLoader` / `SkillInfo` | 技能摘要注入系统提示词 |
| 4 | `tools-plugin` | `ToolService` / `TokenUsageService` | 工具定义、工具执行、token 统计 |
| 5 | `models-plugin` | `LlmService`（`HTTPProvider`） | LLM 调用（chat / chatStream） |
| 6 | `evolution-plugin` | `FeedbackManager` / `PromptOptimizer` | 反馈记录、prompt 优化 |
| 7 | `channel-plugin` | `ChannelManager` / `Channel` | 出站消息路由（CLI 通道不触发） |
| 8 | `mcp-plugin` | `MCPServersConfig` | MCP 服务器配置（工具扩展） |
| 9 | `workflow-plugin` | `AgentRuntimeService` | **核心编排**：消息路由 + ReAct 循环 |
| 10 | `ui-plugin` | `ChatController` | **HTTP 入口**：SSE 桥接 |

> `workflow-plugin` 的 `plugin.dependencies` 声明为 `evolution-plugin,models-plugin,mcp-plugin,channel-plugin,sessions-plugin,skills-plugin`，确保上述 1-8 全部先于 workflow 启动。

---

## 3. 完整调用链

### 阶段 0：服务发现（启动期，非每次请求）

```
WorkflowPlugin.start()
  └── new WorkflowRuntime(services)            // 组装所有运行期组件
        ├── ConfigLoader.load()                 // 从 ~/.jclaw/config.json 加载配置
        ├── services.get(ToolService)            // ← tools-plugin 已注册
        ├── new SessionManager(workspace/sessions)
        ├── new ContextBuilder(workspace, ~/.jclaw/skills, null)
        │     ├── setTools(tools)
        │     └── setMemory(services.get(MemoryService))  // ← memory-plugin 已注册
        ├── new ProviderManager(config, ctx, tools, sessions, workspace, services)
        │     └── reloadModel()
        │           ├── config.getModels() → 反查 model → providerName
        │           ├── services.get(LlmService, providerName)  // ← models-plugin 已注册
        │           ├── llm.reload(providerName, apiKey, apiBase)
        │           └── applyProvider(llm)
        │                 ├── new ReActExecutor(llm, tools, sessions, model, ...)
        │                 ├── new SessionSummarizer(sessions, llm, model, ...)
        │                 └── buildOptionalComponents()  // 进化/协同组件（可选）
        ├── new MessageRouter(providerManager, bus, sessions, ctx, config)
        └── new SecurityGuard(workspace, ...)
  └── services.register(AgentRuntimeService, impl, "workflow")  // ← 注册到 ServiceRegistry
```

### 阶段 1：HTTP 入口（ui-plugin）

```
POST /api/chat/stream
  └── ChatController.chatStream(request)              [ui-plugin]
        ├── 解析 message / sessionId / images
        ├── new SseEmitter(300000ms)
        ├── services.get(AgentRuntimeService)         // 从 ServiceRegistry 取 workflow 注册的实现
        └── executor.execute(() -> {                  // 异步线程，不阻塞 Servlet 容器
              rt.processDirectStream(message, sessionId, images, new StreamCallback() {
                  onChunk(chunk)   → emitter.send(data: chunk)
                  onComplete(full) → emitter.send(data: [DONE]); emitter.complete()
                  onError(cause)   → emitter.send(data: [ERROR] ...); emitter.completeWithError()
              })
            })
```

### 阶段 2：Agent 运行期入口（workflow-plugin）

```
AgentRuntimeServiceImpl.processDirectStream(content, sessionKey, images, callback)
  ├── resolveSessionKey(sessionKey)              // null → "cli:<uuid>"
  ├── new InboundMessage("cli", "web", key, content)
  └── runtime.router.routeUserStream(msg, images, callback)
```

### 阶段 3：消息路由（workflow-plugin → 跨插件）

```
MessageRouter.routeUserStream(msg, images, callback)
  │
  ├── ① providerManager.isConfigured()            // 检查 LlmService 是否就绪
  │     └── false → callback.onComplete("⚠️ Provider 未配置")
  │
  ├── ② buildContext(sessionKey, msg)             // 构建上下文（纯文本）
  │   或 buildContextWithImages(sessionKey, msg, images)  // 多模态
  │     └── ContextBuilder.buildMessages(history, summary, content, [images,] channel, chatId)
  │           ├── buildSystemPrompt(currentMessage)
  │           │     ├── IdentitySection           // Agent 身份信息
  │           │     ├── BootstrapSection          // AGENTS.md / SOUL.md 引导文件
  │           │     ├── ToolsSection              // ← tools.getDefinitions()（tools-plugin）
  │           │     ├── SkillsSection             // ← skillsLoader.listSkills()（skills-plugin）
  │           │     └── MemorySection             // ← memory.getMemoryContext()（memory-plugin）
  │           ├── sanitizeHistory(history)        // 清理孤立 tool 消息
  │           ├── processHistoryImages(history)    // 丢弃历史图片（减 token）
  │           └── Message.user(content[, images])  // 当前用户消息
  │
  ├── ③ sessions.addMessage(sessionKey, "user", content)  // 持久化用户消息
  │   sessions.save(sessions.getOrCreate(sessionKey))
  │
  ├── ④ feedbackManager.recordMessageExchange(sessionKey)  // evolution-plugin（可选）
  │
  ├── ⑤ 适配 StreamCallback
  │     └── SDK StreamCallback → LlmService.StreamCallback（onChunk 透传）
  │
  └── ⑥ comps.reActExecutor.executeStream(messages, sessionKey, llmCallback)  → 阶段 4
```

### 阶段 4：ReAct 迭代循环（workflow-plugin → models-plugin → tools-plugin）

```
ReActExecutor.executeStream(messages, sessionKey, callback)
  └── executeLoop(messages, sessionKey, callback,
        │   llmCaller = callLLMStream,
        │   toolExecutor = executeToolCallsWithStream)
        │
        ├── while (iteration < maxIterations):
        │     │
        │     ├── 检查 aborted 标志                    // /chat/abort 可中断
        │     │
        │     ├── ① callLLMStream(messages, callback)
        │     │   └── provider.chatStream(messages, toolDefs, model, options, callback)
        │     │       └── HTTPProvider.chatStream()    [models-plugin]
        │     │             ├── requestBuilder.buildRequestBody(messages, tools, model, options)
        │     │             ├── requestBody.put("stream", true)
        │     │             ├── buildHttpRequest(requestJson)   // POST {apiBase}/chat/completions
        │     │             ├── httpClient.newCall(request).execute()  // OkHttp 同步调用
        │     │             └── responseParser.parseStreamResponse(body, callback)
        │     │                   └── 逐 chunk 调用 callback.onChunk(token)
        │     │                         └── 透传到 SDK StreamCallback.onChunk(chunk)
        │     │                               └── SseEmitter.send(data: token)  [ui-plugin]
        │     │
        │     ├── ② recordTokenUsage(response)         // → TokenUsageService（tools-plugin）
        │     │
        │     ├── ③ XML fallback：若模型不支持 function calling
        │     │   └── XmlToolCallParser.parseXmlToolCalls(content)
        │     │
        │     ├── ④ 无工具调用 → 返回 finalContent（流式已完成）
        │     │   ├── 空响应保护：重试 ≤ 2 次
        │     │   └── 兜底提示：EMPTY_RESPONSE_FALLBACK
        │     │
        │     └── ⑤ 有工具调用 → 执行工具并继续迭代
        │           ├── addAssistantMessage(messages, response, sessionKey)
        │           │   └── sessions.addFullMessage()  // 持久化 assistant 消息
        │           │
        │           └── executeToolCallsWithStream(messages, toolCalls, sessionKey, iteration)
        │                 └── for each toolCall:
        │                       ├── enhancedCallback.onEvent(toolStart)   // 流式事件
        │                       ├── setToolContext(toolName, sessionKey, callback)
        │                       │   ├── ToolContextAware.setChannelContext()  // 工具上下文
        │                       │   └── StreamAwareTool.setStreamCallback()  // 流式回调
        │                       ├── tools.execute(toolName, args)     // ← tools-plugin
        │                       ├── enhancedCallback.onEvent(toolEnd)   // 流式事件
        │                       ├── sessions.addToolCallRecord()        // 工具调用记录
        │                       └── messages.add(Message.tool(id, result))
        │
        └── 返回 finalContent
```

### 阶段 5：收尾（workflow-plugin → sessions-plugin → evolution-plugin）

```
MessageRouter.routeUserStream()（executeStream 返回后）
  ├── ensureNonBlank(response)                           // 空响应兜底
  ├── persistAndSummarize(sessionKey, response)
  │     ├── sessions.addMessage(sessionKey, "assistant", response)  // 持久化回复
  │     ├── sessions.save(...)
  │     └── summarizer.maybeSummarize(sessionKey)
  │           └── 异步线程：
  │                 ├── shouldSummarize(history)           // 消息数/token 阈值
  │                 ├── summarize(sessionKey)
  │                 │   ├── provider.chat(summaryMessages, ...)  // ← models-plugin
  │                 │   └── mergeSummaries()（大量消息分批）
  │                 ├── sessions.setSummary() / truncateHistory()
  │                 └── memoryStore.addEntry(summary, ...)   // ← memory-plugin
  │
  ├── publishReplyIfNeeded(msg, response)
  │     └── channel="cli" → 不发布到出站 bus（避免 SSE 重复）
  │
  └── callback.onComplete(response)
        └── SseEmitter.send(data: [DONE]); emitter.complete()  [ui-plugin]
```

---

## 4. 插件调用顺序总结

按**实际执行时序**排列（一次完整的流式请求）：

```
ui-plugin          → HTTP 入口，SSE 桥接
  workflow-plugin   → AgentRuntimeService 入口
    sessions-plugin → 读取会话历史 / 写入用户消息
    skills-plugin   → 技能摘要注入系统提示词
    memory-plugin   → 记忆上下文检索
    tools-plugin    → 工具定义列表（getDefinitions）
    models-plugin   → LLM 流式调用（chatStream）
      ↑↓            → token 逐 chunk 回传（callback.onChunk）
    tools-plugin    → 工具执行（若有 tool_calls）
    evolution-plugin→ 反馈记录（recordToolResult / recordMessageExchange）
    sessions-plugin → 持久化 assistant 回复 / 工具调用记录
    models-plugin   → 会话摘要（异步 maybeSummarize，可能再次调用 LLM）
    memory-plugin   → 摘要写入长期记忆（异步）
  ui-plugin         → SSE 发送 [DONE]，结束 emitter
```

---

## 5. 关键设计点

- **Service 契约解耦**：ui-plugin 不直接引用 workflow-plugin 类，通过 `ServiceRegistry.get(AgentRuntimeService.class)` 获取实现，跨插件 ClassLoader 安全。
- **异步线程**：`ChatController` 用 `ExecutorService` 异步执行，`SseEmitter` 超时 5 分钟，不阻塞 Servlet 容器。
- **channel=cli**：`AgentRuntimeServiceImpl` 固定 `channel="cli"`，使 `publishReplyIfNeeded` 跳过出站 bus 发布，避免 SSE + 通道重复发送。
- **ReAct 循环**：`ReActExecutor.executeLoop` 支持工具调用迭代（默认 maxIterations），流式与非流式共用同一循环逻辑。
- **空响应保护**：LLM 返回空内容时重试 ≤ 2 次，耗尽后返回兜底提示。
- **XML fallback**：模型不支持 function calling 时，从响应文本中解析 XML 格式工具调用。
- **会话摘要**：`SessionSummarizer` 异步执行，达阈值后压缩历史，结果写入 memory-plugin 长期记忆。
- **中断机制**：`/api/chat/abort` 设置 `ReActExecutor.aborted` 标志，循环在下一次迭代开始时退出。
```
