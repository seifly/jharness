package cn.seifly.jharness.plugins.workflow;

import cn.seifly.jharness.plugins.evolution.FeedbackManager;
import cn.seifly.jharness.plugins.evolution.PromptOptimizer;

/**
 * Provider 切换时一次性创建的组件集合。
 *
 * <p>将 {@link AgentRuntimeServiceImpl} 中散落的组件构造逻辑收敛到此处，
 * 使上层只需持有引用、不再感知各组件的构造细节。所有字段均为包级可见，仅供 workflow 包内部使用。</p>
 *
 * <p>#2 重构后：移除 {@code memoryEvolver} / {@code tokenUsageStore} 两个字段——
 * 前者在 workflow 内从未被调用（死存储），后者已下沉为 ReActExecutor 内部持有的
 * SDK {@link cn.seifly.jharness.plugin.framework.tools.TokenUsageService} 契约，
 * 外部无需再通过本集合访问。</p>
 */
class ProviderComponents {

    final ReActExecutor reActExecutor;
    final SessionSummarizer summarizer;

    /* ---------- 进化组件（可选） ---------- */
    final FeedbackManager feedbackManager;
    final PromptOptimizer promptOptimizer;

    /* ---------- 协同组件（可选） ---------- */
    final AgentOrchestrator orchestrator;

    ProviderComponents(
            ReActExecutor reActExecutor,
            SessionSummarizer summarizer,
            FeedbackManager feedbackManager,
            PromptOptimizer promptOptimizer,
            AgentOrchestrator orchestrator) {
        this.reActExecutor = reActExecutor;
        this.summarizer = summarizer;
        this.feedbackManager = feedbackManager;
        this.promptOptimizer = promptOptimizer;
        this.orchestrator = orchestrator;
    }
}
