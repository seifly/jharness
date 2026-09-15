# tools-plugin

工具集成插件，为 Agent 提供可调用的工具集（Tool）。本插件仅创建并注册空的 `ToolRegistry` 作为 `ToolService` 契约，具体 Tool 实现由 `workflow-plugin` 在 `WorkflowRuntime` 中实例化并注册到本注册表。

## weather skill 调用流程

以用户询问「杭州天气」为例，完整调用链如下：

```
用户消息: "杭州天气"
    │
    ▼
[1] LLM 决策调用 skills 工具
    ReActExecutor.executeToolCallsWithStream()                 workflow-plugin/.../ReActExecutor.java:449
      └─> toolCall = {name="skills", args={action="invoke", name="weather"}}
      └─> StreamEvent.toolStart("skills", args)                workflow-plugin/.../ReActExecutor.java:465
      └─> executeToolCallWithStream(toolCall, sessionKey)      workflow-plugin/.../ReActExecutor.java:533
            │
            ▼
[2] ToolService 路由到 SkillsTool.execute()
    ToolRegistry.execute("skills", args)                       tools-plugin/.../ToolRegistry.java:73
      └─> tool.execute(args)                                   tools-plugin/.../ToolRegistry.java:81
            │
            ▼
[3] SkillsTool 解析并返回 SKILL.md 内容 + base-path
    SkillsTool.execute(args)                                   tools-plugin/.../SkillsTool.java:138
      └─> case "invoke" -> executeInvoke(args)                 tools-plugin/.../SkillsTool.java:144
            └─> SkillsTool.executeInvoke(args)                 tools-plugin/.../SkillsTool.java:206
                  ├─> skillsLoader.loadSkill("weather")       skills-plugin/.../SkillsLoader.java:187
                  │     └─> loadBuiltinSkillContent("weather") skills-plugin/.../SkillsLoader.java:132
                  │           (从 classpath 加载 skills/weather/SKILL.md)
                  ├─> findSkillLocation("weather")             tools-plugin/.../SkillsTool.java:234
                  │     └─> SkillsLoader.extractBuiltinSkillToFileSystem("weather")
                  │           (解压 builtin 技能到 workspace/.builtin-cache/weather/)
                  └─> 返回 <skill-invocation> XML + SKILL.md 全文 + base-path
                        tools-plugin/.../SkillsTool.java:219-231
            │
            ▼
[4] 工具结果回写为 tool 消息，LLM 进行下一轮决策
    StreamEvent.toolEnd("skills", result, success=true)       workflow-plugin/.../ReActExecutor.java:474
    Message.tool(toolCall.getId(), result)                    workflow-plugin/.../ReActExecutor.java:488
    sessions.addFullMessage(sessionKey, toolResultMsg)        workflow-plugin/.../ReActExecutor.java:490
    │
    ▼
[5] LLM 读取 SKILL.md 中的 curl 指令，决策调用 exec 工具
    ReActExecutor.executeToolCallsWithStream()                 workflow-plugin/.../ReActExecutor.java:449
      └─> toolCall = {name="exec", args={command="curl -s 'wttr.in/Hangzhou?format=%l:+%c+%t+%h+%w'"}}
      └─> executeToolCallWithStream(toolCall, sessionKey)      workflow-plugin/.../ReActExecutor.java:533
            │
            ▼
[6] ToolService 路由到 ExecTool.execute()
    ToolRegistry.execute("exec", args)                         tools-plugin/.../ToolRegistry.java:73
      └─> tool.execute(args)                                   tools-plugin/.../ToolRegistry.java:81
            │
            ▼
[7] ExecTool 执行 curl 命令并返回天气数据
    ExecTool.execute(args)                                     tools-plugin/.../ExecTool.java:83
      ├─> resolveWorkingDir(args.get("working_dir"))           tools-plugin/.../ExecTool.java:111
      ├─> performSecurityChecks(command, cwd)                  tools-plugin/.../ExecTool.java:124
      │     └─> SecurityGuard.checkCommand(command)           (via guardCommand)              tools-plugin/.../ExecTool.java:269
      └─> executeCommand(command, cwd)                        tools-plugin/.../ExecTool.java:140
            ├─> buildProcess(command, cwd)                    tools-plugin/.../ExecTool.java:171
            │     └─> sh -c "curl -s 'wttr.in/Hangzhou?...'"
            ├─> captureOutput(process)                        tools-plugin/.../ExecTool.java:196
            ├─> process.waitFor(60s)                          tools-plugin/.../ExecTool.java:148
            └─> buildResult(output, exitCode)                  tools-plugin/.../ExecTool.java:232
                  └─> "Hangzhou: ⛅️ +25°C 60% ↙5km/h\n..."
            │
            ▼
[8] 工具结果回写，LLM 整理回复用户
    StreamEvent.toolEnd("exec", result, success=true)         workflow-plugin/.../ReActExecutor.java:474
    Message.tool(toolCall.getId(), result)                    workflow-plugin/.../ReActExecutor.java:488
    │
    ▼
[9] LLM 生成最终自然语言回复
    "杭州现在多云，气温 25°C，湿度 60%，风向西南偏南，风速 5km/h。"
```

## 工具注册位置

标准工具集在 `WorkflowRuntime` 构造期注册（`SecurityGuard` 与 `SkillsLoader` 就绪后）：

- `WorkflowRuntime.registerStandardTools()`             workflow-plugin/.../WorkflowRuntime.java:252
  - `tools.register(new ExecTool(workspace, guard))`     workflow-plugin/.../WorkflowRuntime.java:256
  - `tools.register(new SkillsTool(workspace, loader))` workflow-plugin/.../WorkflowRuntime.java:260
  - `ReadFileTool` / `WriteFileTool` / `EditFileTool` / `ListDirTool`（共享 `SecurityGuard`）
  - `WebFetchTool` / `WebSearchTool`（独立 OkHttpClient）

## 工具清单

| 工具名 | 实现类 | 功能 |
|--------|--------|------|
| `exec` | `ExecTool` | 执行 Shell 命令（受 `SecurityGuard` 黑名单约束） |
| `skills` | `SkillsTool` | 技能管理：list / invoke / search / install / create / edit / remove |
| `read_file` | `ReadFileTool` | 读取文件内容（受工作空间沙箱约束） |
| `write_file` | `WriteFileTool` | 写入文件（受工作空间沙箱约束） |
| `edit_file` | `EditFileTool` | 文本替换编辑（受工作空间沙箱约束） |
| `list_dir` | `ListDirTool` | 列出目录内容（受工作空间沙箱约束） |
| `web_fetch` | `WebFetchTool` | 抓取网页并提取可读文本 |
| `web_search` | `WebSearchTool` | Brave Search API 网络搜索 |

## 架构说明

```
workflow-plugin                    tools-plugin                    skills-plugin
─────────────────                  ─────────────────              ──────────────
WorkflowRuntime                    ToolsPlugin.start()             SkillsLoader
  │                                  │                              ▲
  ├─ SecurityGuard ─────────────┐    ├─ ToolRegistry (空)          │
  ├─ SkillsLoader ──────────────┼──┐ └─ 注册为 ToolService ──┐     │
  │                              │  │                         │     │
  └─ registerStandardTools() ───┤  │                         │     │
         │                       │  │                         │     │
         ├─ new ExecTool ────────┘  │                         │     │
         │   (guard, workspace)     │                         │     │
         ├─ new SkillsTool ─────────┘                          │     │
         │   (workspace, loader) ◄────────────────────────────┘     │
         ├─ new ReadFileTool(guard)                                 │
         ├─ new WriteFileTool(guard)                                │
         ├─ new EditFileTool(guard)                                 │
         └─ new ListDirTool(guard)                                  │
                                                                   │
ReActExecutor ──> ToolService.execute(name, args) ──> Tool.execute(args)
```

- **`ToolsPlugin.start()`** 仅创建空 `ToolRegistry` 并注册为 `ToolService`，不实例化任何具体 Tool。
- **`WorkflowRuntime.registerStandardTools()`** 在 `SecurityGuard` 和 `SkillsLoader` 就绪后，把标准工具集注册到 `ToolService`。
- **`ToolRegistry.execute()`** 作为统一入口，按工具名路由到对应 `Tool.execute()`。
- **`SecurityGuard`** 被 `ExecTool` 和文件类工具共享，UI 通过 `SecurityService` 更新策略时实时生效。
- **`SkillsLoader`** 被 `SkillsTool` 和 `ContextBuilder` 共享，技能列表视图一致。
