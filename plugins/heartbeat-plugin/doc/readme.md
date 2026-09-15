# 心跳（Heartbeat）机制说明

## 1. 心跳的作用

在 Agent 场景中，传统模式是"用户问 → Agent 答"（被动响应）。心跳机制打破了这一点，让 Agent 具备**主动性**：

1. **定期自检** —— 按配置的间隔（秒级）周期性醒来，检查是否有需要关注的任务
2. **记忆审查** —— 读取工作区 `memory/HEARTBEAT.md` 的笔记内容，结合当前时间，拼装成一段"心跳提示"，交给回调函数（通常是 LLM Provider）判断该做什么
3. **主动行动** —— 回调函数返回的处理结果可驱动 Agent 执行下一步（如处理队列任务、优化提示词、发通知等）
4. **存活检测** —— 守护线程在后台持续运行，也充当 Agent 进程"活着"的信号

> **一句话总结**：心跳 = Agent 的"闹钟 + 主动思考入口"，让 Agent 从被动应答变成主动行动者。

## 2. jharness 中 heartbeat-plugin 的实现

核心类：`HeartbeatService`（位于 `plugins/heartbeat-plugin/src/main/java/cn/seifly/jharness/plugins/heartbeat/`）

### 工作机制

- 启动时创建一个 **daemon 守护线程**（`runLoop()`），`while(running)` + `Thread.sleep(interval * 1000)` 构成定时循环，不影响主进程退出
- 每个周期调用 `checkHeartbeat()`：构建提示 → 调用 `onHeartbeat` 回调（`Function<String, String>`，把提示串喂给 LLM）→ 返回处理结果
- 失败不中断：回调异常只记录到 `memory/heartbeat.log`，循环继续（容错设计）

### 关键设计点

| 设计 | 位置 | 说明 |
|------|------|------|
| 可插拔回调 | `onHeartbeat` | 心跳逻辑与业务解耦，调用方传入任何 `Function` |
| 线程安全启停 | `ReentrantLock` + `volatile running` | `start()`/`stop()` 可重复调用不报错 |
| 心跳提示模板 | `buildPrompt()` | 固定三段式："检查任务 → 审查记忆 → 识别问题"，再附加 `HEARTBEAT.md` 内容 |
| 错误落盘 | `log()` | 回调失败写入 `memory/heartbeat.log`，方便事后排查 |

## 3. jclaw 项目中心跳的实际使用情况

`/Users/seifly/work/java/jclaw` 项目中的"心跳"分**三层**，其中 `HeartbeatService` **是一个"未接线的备件"，并未真正运行**。

### 3.1 Agent 心跳服务（HeartbeatService）——已实现但未接入

`HeartbeatService.java` 本身是完整实现（守护线程 + `runLoop()` 周期循环 + `onHeartbeat` 回调 + 日志落盘），但**整个项目搜索不到任何 `new HeartbeatService(...)` 实例化调用**。真正在用的是它的"配套基础设施"：

| 设施 | 位置 | 状态 |
|------|------|------|
| 开关配置 `heartbeatEnabled` | `AgentConfig.java`（默认 **false**） | 有 |
| 外部化配置 `jclaw.agent.heartbeat-enabled` | `JClawProperties` / `JClawConfig` | 有 |
| REST 读写接口 | `ConfigController` GET/PUT `/api/config/agent` | 有 |
| 前端开关 UI | `web/index.html` / `app.js` 启用心跳下拉框 | 有 |
| 记忆文件 `HEARTBEAT.md` | `WebUtils`/`WorkspaceController` 预置到工作空间 | 有 |

也就是说：**开关、配置、界面、记忆文件全部就位，但没有任何代码把 `HeartbeatService` 实例化并接上回调**。开启开关目前不会产生实际行为——该类更像为将来 Agent 主动行动预留的框架。

### 3.2 通道协议层心跳——真实在运行（与 Agent 心跳无关）

## 4. 后续建议

若要让 Agent 心跳真正生效，需要在启动流程（如 `AgentRuntime` 或某个 `@Component` 的 `@PostConstruct`）里实例化 `HeartbeatService`，并把 LLM 调用链作为 `onHeartbeat` 回调传入。
