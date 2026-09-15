package cn.seifly.jharness.plugins.workflow;

import cn.seifly.jharness.plugin.framework.config.EvolutionConfig;
import cn.seifly.jharness.plugin.framework.config.ModelsConfig;
import cn.seifly.jharness.plugin.framework.config.ProvidersConfig;
import cn.seifly.jharness.plugins.evolution.FeedbackManager;
import cn.seifly.jharness.plugins.evolution.PromptOptimizer;
import cn.seifly.jharness.plugin.framework.config.Config;
import cn.seifly.jharness.plugin.framework.llm.LlmService;
import cn.seifly.jharness.plugin.framework.memory.MemoryService;
import cn.seifly.jharness.plugin.framework.service.ServiceRegistry;
import cn.seifly.jharness.plugin.framework.tools.ToolService;
import cn.seifly.jharness.plugin.framework.tools.TokenUsageService;
import lombok.extern.slf4j.Slf4j;
import cn.seifly.jharness.plugins.sessions.SessionManager;

import java.util.Optional;

/**
 * Provider 管理器，负责 LLM Provider 的初始化、热重载和组件构建。
 *
 * <p>将 Provider 相关的职责从 {@code AgentRuntime} 中抽取出来，
 * 使 AgentRuntime 专注于消息路由和生命周期管理。</p>
 *
 * <p>#1 重构后不再 {@code new HTTPProvider(...)}，而是通过 {@link ServiceRegistry}
 * 获取由 models-plugin 注册的 {@link LlmService} 实现，调用 {@link LlmService#reload}
 * 推入凭证，从而解除对 models-plugin 实现类的编译期依赖（仍 provided 依赖其
 * {@code config.ProvidersConfig / config.ModelsConfig} 配置类）。</p>
 *
 * <p>#2 重构后：tools 字段类型提升到 SDK {@link ToolService}；TokenUsageStore /
 * MemoryStore 的具体实现也替换为 SDK 契约 {@link TokenUsageService} /
 * {@link MemoryService}，通过 {@link ServiceRegistry} 取得并 push workspace 路径，
 * 不再编译期依赖 tools-plugin / memory-plugin 实现类。MemoryEvolver 在 workflow 内
 * 未被调用，已删除（记忆进化由 memory-plugin 自身或 heartbeat-plugin 触发）。</p>
 */
@Slf4j
class ProviderManager {

    /* ---------- 依赖 ---------- */
    private final Config config;
    private final ContextBuilder contextBuilder;
    private final ToolService tools;
    private final SessionManager sessions;
    private final String workspace;
    private final ServiceRegistry services;

    /* ---------- Provider 相关字段 ---------- */
    private volatile LlmService provider;
    private volatile boolean providerConfigured = false;
    private final Object providerLock = new Object();
    private volatile ProviderComponents components;

    ProviderManager(Config config, ContextBuilder contextBuilder, ToolService tools,
                    SessionManager sessions, String workspace, ServiceRegistry services) {
        this.config = config;
        this.contextBuilder = contextBuilder;
        this.tools = tools;
        this.sessions = sessions;
        this.workspace = workspace;
        this.services = services;
    }

    // ==================== Provider 管理 ====================

    /** 动态设置或替换 LLM Provider，线程安全。 */
    void setProvider(LlmService provider) {
        if (provider == null) {
            return;
        }
        synchronized (providerLock) {
            applyProvider(provider);
            log.info("Provider configured dynamically: model={}", config.getAgent().getModel());
        }
    }

    /**
     * 根据当前 config 中的 provider/model 配置热重载 LLM Provider，无需重启即可生效。
     *
     * <p>优先从 ModelsConfig 中通过 model 名称反查对应的 provider，保证 api_base 与 model
     * 始终来自同一个绑定关系，避免 AgentConfig.provider 与 model 手动错配的问题。
     * 若 model 未在 ModelsConfig 中定义，则 fallback 到 AgentConfig.provider。</p>
     *
     * <p>#1 重构：不再 {@code new HTTPProvider(...)}，而是通过 {@link ServiceRegistry}
     * 取得由 models-plugin 注册的 {@link LlmService}，调用 {@link LlmService#reload}
     * 推入 {@code apiKey / apiBase}，再交给 {@link #applyProvider} 构建下游组件。</p>
     *
     * @return true 表示重载成功，false 表示 provider 未配置或无效
     */
    boolean reloadModel() {
        String modelName = config.getAgent().getModel();
        String providerName = resolveProviderName(modelName);

        if (providerName == null || providerName.isEmpty()) {
            log.warn("reloadModel skipped: provider name could not be resolved: model={}", modelName);
            return false;
        }

        ProvidersConfig.ProviderConfig providerConfig = config.getProviders().getByName(providerName);
        if (providerConfig == null || !providerConfig.isValid()) {
            log.warn("reloadModel skipped: provider not configured or invalid: provider={}, model={}",
                    providerName, modelName);
            return false;
        }

        String apiBase = providerConfig.getApiBaseOrDefault(ProvidersConfig.getDefaultApiBase(providerName));

        // 通过 ServiceRegistry 获取 LlmService（由 models-plugin 注册）；
        // 优先按 providerName 取（未来支持 deepseek/anthropic 等独立 Provider 插件），回退首个
        LlmService llm = resolveLlmService(providerName);
        if (llm == null) {
            log.warn("reloadModel skipped: LlmService 未注册: provider={}, model={}", providerName, modelName);
            return false;
        }

        // 推入凭证（消费方 push 模型），再触发下游组件重建
        llm.reload(providerName, providerConfig.getApiKey(), apiBase);
        setProvider(llm);

        log.info("Model reloaded successfully: provider={}, model={}", providerName, modelName);
        return true;
    }

    /**
     * 解析已注册的 {@link LlmService}：优先按 providerName 取，回退首个。
     */
    private LlmService resolveLlmService(String providerName) {
        Optional<LlmService> byName = services.get(LlmService.class, providerName);
        if (byName.isPresent()) {
            return byName.get();
        }
        return services.get(LlmService.class).orElse(null);
    }

    /**
     * 从 ModelsConfig 中反查 model 对应的 provider 名称。
     * 若 model 未在 ModelsConfig 中定义，则 fallback 到 AgentConfig.provider。
     */
    private String resolveProviderName(String modelName) {
        ModelsConfig.ModelDefinition modelDef = config.getModels().getDefinitions().get(modelName);
        if (modelDef != null) {
            return modelDef.getProvider();
        }
        String fallback = config.getAgent().getProvider();
        log.warn("reloadModel: model not found in ModelsConfig, falling back to agent config provider: model={}, fallback_provider={}",
                modelName, fallback != null ? fallback : "");
        return fallback;
    }

    /**
     * 从 ModelsConfig 中解析当前模型的上下文窗口大小。
     *
     * 优先使用 ModelsConfig 中配置的 maxContextSize，
     * 若模型未在配置中定义则 fallback 到 DEFAULT_CONTEXT_WINDOW。
     *
     * @param model 模型名称
     * @return 上下文窗口 token 数
     */
    private int resolveContextWindow(String model) {
        ModelsConfig.ModelDefinition definition = config.getModels().getDefinitions().get(model);
        if (definition != null && definition.getMaxContextSize() != null) {
            return definition.getMaxContextSize();
        }
        log.warn("Model not found in ModelsConfig, using default context window: model={}, default={}",
                model, AgentConstants.DEFAULT_CONTEXT_WINDOW);
        return AgentConstants.DEFAULT_CONTEXT_WINDOW;
    }

    /**
     * 将 provider 及其派生组件一次性赋值，消除构造器与 setProvider 之间的重复逻辑。
     * 调用方需自行保证线程安全（构造器天然安全，setProvider 通过 providerLock 保护）。
     */
    private void applyProvider(LlmService newProvider) {
        this.provider = newProvider;

        String model = config.getAgent().getModel();
        int maxIterations = config.getAgent().getMaxToolIterations();
        int contextWindow = resolveContextWindow(model);
        String providerName = resolveProviderName(model);

        // 同步上下文窗口到 ContextBuilder，用于计算记忆 token 预算
        contextBuilder.setContextWindow(contextWindow);

        // #2 重构：memory / tokenUsage 改为 SDK 契约，由 services 取得并 push workspace
        MemoryService memoryStore = contextBuilder.getMemoryStore();

        TokenUsageService tokenUsageService = services.get(TokenUsageService.class)
                .orElseGet(ProviderManager::emptyTokenUsage);
        tokenUsageService.init(workspace);

        ReActExecutor reActExecutor = new ReActExecutor(newProvider, tools, sessions, model, providerName, maxIterations);
        reActExecutor.setTokenUsageService(tokenUsageService);

        SessionSummarizer summarizer = new SessionSummarizer(
                sessions, newProvider, model, contextWindow, memoryStore);

        this.components = buildOptionalComponents(
                newProvider, model, maxIterations, reActExecutor, summarizer);

        this.providerConfigured = true;
    }

    /**
     * 构建完整的 {@link ProviderComponents}，包含核心组件与可选的进化/协同组件。
     *
     * <p>将各可选功能的初始化逻辑收敛在此处，使 {@link #applyProvider} 保持高层编排视角，
     * 不感知各组件的构造细节。</p>
     */
    private ProviderComponents buildOptionalComponents(
            LlmService newProvider, String model, int maxIterations,
            ReActExecutor reActExecutor, SessionSummarizer summarizer) {

        FeedbackManager feedbackManager = null;
        PromptOptimizer promptOptimizer = null;
        AgentOrchestrator orchestrator = null;

        // 进化组件（反馈收集 + Prompt 优化）
        EvolutionConfig evolutionConfig = config.getAgent().getEvolution();
        if (evolutionConfig != null && evolutionConfig.isAnyEvolutionEnabled()) {
            if (evolutionConfig.isFeedbackEnabled()) {
                feedbackManager = new FeedbackManager(workspace, evolutionConfig);
                reActExecutor.setFeedbackManager(feedbackManager);
                log.info("Feedback collection enabled");
            }
            if (evolutionConfig.isPromptOptimizationEnabled() && feedbackManager != null) {
                promptOptimizer = new PromptOptimizer(
                        newProvider, model, workspace, feedbackManager, evolutionConfig);
                contextBuilder.setPromptOptimizer(promptOptimizer);
                log.info("Prompt optimization enabled");
            }
        } else {
            if (log.isDebugEnabled()) {
                log.debug("Evolution features disabled");
            }
        }

        // 协同组件（多 Agent 编排）
        if (config.getAgent().isCollaborationEnabled()) {
            orchestrator = new AgentOrchestrator(newProvider, tools, workspace, model, maxIterations);

            // 注入会话管理器，使协同结论可回流到主会话历史
            orchestrator.setCallerSessionManager(sessions);

            // 注入反馈管理器（如果已启用），使协同结果可驱动 Agent 自我进化
            if (feedbackManager != null) {
                orchestrator.setFeedbackManager(feedbackManager);
            }

            log.info("Collaboration features enabled: supportedModes={}", "debate,team,roleplay,consensus,hierarchy");
        } else {
            if (log.isDebugEnabled()) {
                log.debug("Collaboration features disabled");
            }
        }

        return new ProviderComponents(reActExecutor, summarizer,
                feedbackManager, promptOptimizer, orchestrator);
    }

    // ==================== Getter 方法 ====================

    LlmService getProvider() {
        return provider;
    }

    boolean isConfigured() {
        return providerConfigured;
    }

    ProviderComponents getComponents() {
        return components;
    }

    // ==================== no-op 兜底实现 ====================

    /**
     * 空 {@link TokenUsageService} 兜底实现：init / record 均为 no-op。
     *
     * <p>当 tools-plugin 未启动或未注册 TokenUsageService 时使用，
     * 使 workflow 能在无 token 记录环境下启动（ReActExecutor 的 token 统计静默跳过）。
     */
    private static TokenUsageService emptyTokenUsage() {
        return new TokenUsageService() {
            @Override
            public void init(String workspace) { /* no-op */ }
            @Override
            public void record(String provider, String model, int promptTokens, int completionTokens) { /* no-op */ }
        };
    }
}
