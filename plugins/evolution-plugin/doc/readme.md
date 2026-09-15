# evolution-plugin 使用情况说明

## 1. 结论

> **evolution-plugin 被 workflow-plugin 引用（编译期 + 条件运行期），但默认配置下"加载了却不生效"** —— 所有进化功能开关默认关闭，除非显式开启 `agent.evolution.feedbackEnabled` 或 `promptOptimizationEnabled`。

## 2. 使用链路证据

### 2.1 编译期引用（确凿）

workflow-plugin 的 7 个类通过 `provided` 依赖引用了 evolution-plugin 的 3 个类：

```java
import cn.seifly.jharness.plugins.evolution.FeedbackManager;
import cn.seifly.jharness.plugins.evolution.PromptOptimizer;
```

| 被引用类（evolution-plugin） | 引用方（workflow-plugin） |
|------|------|
| `FeedbackManager` | `ProviderManager`、`ReActExecutor` |
| `PromptOptimizer` | `ProviderManager`、`ContextBuilder` |
| `EvaluationFeedback` | `AgentOrchestrator`、`IdentitySection`、`SectionContext`、`ProviderComponents` |

### 2.2 运行期依赖声明

workflow-plugin 的 `plugin.properties` 声明了 `plugin.dependencies=evolution-plugin,...`，SpringPluginManager 按依赖拓扑先加载 evolution-plugin，避免运行期 `NoClassDefFoundError`。**所以插件必然被 PF4J 加载**，这是"使用了"的直接证据。

### 2.3 运行期调用是条件触发（关键）

`ProviderManager` 中实例化逻辑被开关门控：

- `evolutionConfig.isFeedbackEnabled()` 为 true → `new FeedbackManager` + `reActExecutor.setFeedbackManager(...)`
- `evolutionConfig.isPromptOptimizationEnabled()` 为 true → `new PromptOptimizer` + `contextBuilder.setPromptOptimizer(...)`

而框架层 `EvolutionConfig`（plugin-framework 版本，非 evolution-plugin 内部版本）**默认值全部为 false**：

```java
private boolean feedbackEnabled = false;
private boolean promptOptimizationEnabled = false;
```

且 `plugin-app` 的 resources 下**没有任何 `agent.evolution` 配置** → 走默认关闭路径 → 运行期 `FeedbackManager` / `PromptOptimizer` **不会被实例化**。

同样，`AgentOrchestrator.recordCollaborationFeedback` 里 `feedbackManager == null` 直接 return，默认空转。

### 2.4 其他引用（未启用）

memory-plugin 的 pom 中有一段**被注释掉**的依赖（TODO：`MemoryEvolver.evolveWithFeedback(EvaluationFeedback)` 计划依赖 evolution-plugin）—— 属于规划中、未落地。

## 3. 总结

| 层面 | 状态 |
|------|------|
| 插件加载（PF4J） | ✅ 已加载（workflow-plugin 依赖它） |
| 编译期引用 | ✅ 已引用（3 个类、7 个文件） |
| 运行期执行 | ⚠️ 默认不执行（`EvolutionConfig` 全关） |
| 触发条件 | 需在配置中显式开启 `agent.evolution.feedbackEnabled` / `promptOptimizationEnabled` |

**判定：evolution-plugin 是"已接线、默认断电"的状态** —— 它服务于 workflow-plugin 的自进化能力（反馈收集、Prompt 优化），但要真正跑起来必须在配置里打开开关。
