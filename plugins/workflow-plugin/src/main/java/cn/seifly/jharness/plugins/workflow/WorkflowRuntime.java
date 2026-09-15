package cn.seifly.jharness.plugins.workflow;

import cn.seifly.jharness.plugin.framework.bus.BusClosedException;
import cn.seifly.jharness.plugin.framework.bus.InboundMessage;
import cn.seifly.jharness.plugin.framework.bus.MessageBus;
import cn.seifly.jharness.plugin.framework.llm.ToolDefinition;
import cn.seifly.jharness.plugin.framework.memory.MemoryService;
import cn.seifly.jharness.plugin.framework.security.SecurityGuard;
import cn.seifly.jharness.plugin.framework.security.SecurityService;
import cn.seifly.jharness.plugin.framework.service.ServiceRegistry;
import cn.seifly.jharness.plugin.framework.tools.Tool;
import cn.seifly.jharness.plugin.framework.tools.ToolService;
import cn.seifly.jharness.plugin.framework.tools.TokenUsageService;
import cn.seifly.jharness.plugins.sessions.SessionManager;
import cn.seifly.jharness.plugin.framework.config.Config;
import cn.seifly.jharness.plugins.tools.EditFileTool;
import cn.seifly.jharness.plugins.tools.ExecTool;
import cn.seifly.jharness.plugins.tools.ListDirTool;
import cn.seifly.jharness.plugins.tools.ReadFileTool;
import cn.seifly.jharness.plugins.tools.SkillsTool;
import cn.seifly.jharness.plugins.tools.WebFetchTool;
import cn.seifly.jharness.plugins.tools.WebSearchTool;
import cn.seifly.jharness.plugins.tools.WriteFileTool;
import cn.seifly.jharness.plugins.workflow.config.ConfigLoader;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 工作流运行期组装器。
 *
 * <p>把跨插件的 {@code Config} / {@code ContextBuilder} / {@code ToolService} /
 * {@code SessionManager} / {@code MessageBus} / {@code ProviderManager} /
 * {@code MessageRouter} 在一处按依赖顺序构造完成，并暴露给
 * {@link AgentRuntimeServiceImpl} 使用。
 *
 * <p>该类只在 {@link WorkflowPlugin#start()} 内 new 一次，{@code stop()} 时随插件卸载，
 * 不实现任何生命周期回调——所有清理由插件层 {@code unregister} + GC 完成。
 *
 * <p>#2 重构后：tools / memory / tokenUsage 不再 {@code new} 具体实现类，
 * 而是通过 {@link ServiceRegistry} 取由对应插件注册的 SDK 契约
 * （{@link ToolService} / {@link MemoryService} / {@link TokenUsageService}），
 * 缺失时回退到本类内部的 no-op 兜底实现，解除对 tools-plugin / memory-plugin 的编译期依赖。
 * workspace 路径采用「消费方 push」模型：由本类在取得 MemoryService / TokenUsageService
 * 后调用 {@code init(workspace)} 推入。
 *
 * <p>构造顺序（依赖链）：
 * <pre>
 *   Config  ──> workspace
 *      │
 *      ├──> ToolService（tools-plugin，services.get；缺失走 emptyToolService）
 *      ├──> SessionManager(workspace/sessions)
 *      ├──> ContextBuilder(workspace, ~/.jclaw/skills, null)
 *      │       └──> setTools + setMemory（MemoryService 来自 services.get；缺失走 emptyMemoryService）
 *      │              └──> memory.init(workspace)
 *      ├──> MessageBus（SDK 共享）
 *      └──> ProviderManager(config, ctx, tools, sessions, workspace, services)
 *                                              │
 *                                              └──> MessageRouter(pm, bus, sessions, ctx, config)
 * </pre>
 */
@Slf4j
class WorkflowRuntime {

    final Config config;
    final ContextBuilder contextBuilder;
    final ToolService tools;
    final SessionManager sessions;
    final MessageBus bus;
    final ProviderManager providerManager;
    final MessageRouter router;
    // #4 SecurityService：由本运行期创建的 SecurityGuard 包装实现，供 WorkflowPlugin 注册到
    // ServiceRegistry，ui-plugin 的 ConfigController 通过它实时下发安全策略更新
    final SecurityGuard securityGuard;
    final SecurityService securityService;

    // 入站消息消费者：后台线程从 MessageBus.consumeInbound() 拉取通道消息并交给 router.route() 处理。
    // 由 WorkflowPlugin.start()/stop() 管理生命周期。
    private volatile Thread inboundConsumerThread;
    private volatile boolean inboundConsumerRunning = false;

    WorkflowRuntime(ServiceRegistry services) {
        // 1. Config：从 ~/.jclaw/config.json 加载，缺失或异常则回退默认配置
        this.config = loadConfigOrDefault();

        // 2. 工作空间路径（已展开 ~）
        String workspace = config.getWorkspacePath();

        // 3. 跨插件依赖：tools / sessions / memory
        //    tools / memory / tokenUsage 均通过 ServiceRegistry 取 SDK 契约实现，
        //    缺失时回退 no-op 兜底（PF4J 插件按字母序启动，memory/tools 在 workflow 之前，
        //    但仍需兜底防缺）
        this.tools = services.get(ToolService.class).orElseGet(WorkflowRuntime::emptyToolService);
        this.sessions = new SessionManager(workspace + "/sessions");
        this.contextBuilder = new ContextBuilder(workspace,
                ConfigLoader.expandHome("~/.jclaw/skills"), null);
        this.contextBuilder.setTools(tools);

        // 注入 MemoryService 并推入 workspace 路径（消费方 push 模型）
        MemoryService memory = services.get(MemoryService.class).orElseGet(WorkflowRuntime::emptyMemoryService);
        this.contextBuilder.setMemory(memory);
        memory.init(workspace);

        // 4. MessageBus（SDK 共享）：取 channel-plugin 注册的同一实例，
        //    使本插件 MessageRouter 能消费通道发布的入站消息、并把回复 publish 到出站队列
        //    由 channel-plugin 的 dispatcher 发送。channel-plugin 未启动时回退本地实例（CLI 路径不依赖 bus）。
        this.bus = services.get(MessageBus.class).orElseGet(MessageBus::new);

        // 5. ProviderManager：通过 ServiceRegistry 取 LlmService（由 models-plugin 注册），
        //    不再直接 new HTTPProvider；构造 LLM Provider 链与 ReActExecutor / SessionSummarizer 等组件
        this.providerManager = new ProviderManager(config, contextBuilder, tools, sessions, workspace, services);

        // 6. MessageRouter：消息路由入口，复用 ProviderManager / bus / sessions / ctx / config
        this.router = new MessageRouter(providerManager, bus, sessions, contextBuilder, config);

        // 7. SecurityGuard：基于 Config 的工作空间与命令黑名单策略创建，
        //    包装为 SecurityService 契约供 ui-plugin 实时下发更新（#4）
        this.securityGuard = new SecurityGuard(workspace,
                config.getAgent().isRestrictToWorkspace(),
                config.getAgent().getCommandBlacklist());
        SecurityGuard guard = this.securityGuard;
        this.securityService = new SecurityService() {
            @Override
            public void updateRestrictToWorkspace(boolean restrictToWorkspace) {
                guard.updateRestrictToWorkspace(restrictToWorkspace);
            }
            @Override
            public void updateCommandBlacklist(List<String> customBlacklist) {
                guard.updateCommandBlacklist(customBlacklist);
            }
        };

        // 7.5 注册标准工具到 ToolService（tools-plugin 仅创建空 ToolRegistry，
        //     此处补齐 ExecTool / SkillsTool / 文件类工具 / Web 工具，使用本类已就绪的
        //     SecurityGuard 与 ContextBuilder.getSkillsLoader()）
        registerStandardTools(tools, workspace, guard);

        // 8. best-effort：若 Config 中已配置 Provider，则触发一次 reload 让 Provider 立即可用；
        //    未配置时 reloadModel() 返回 false，无副作用，等待 ui-plugin 写入 Redis 后再触发
        try {
            providerManager.reloadModel();
        } catch (Exception e) {
            log.warn("初始 reloadModel 失败，将在配置就绪后重试: error={}", e.getMessage());
        }

        log.info("WorkflowRuntime 组装完成: workspace={}, provider_configured={}",
                workspace, providerManager.isConfigured());
    }

    private static Config loadConfigOrDefault() {
        try {
            return ConfigLoader.load();
        } catch (IOException e) {
            log.warn("加载 Config 失败，使用默认配置: error={}", e.getMessage());
            return Config.defaultConfig();
        }
    }

    // ==================== 入站消息消费循环 ====================

    /**
     * 启动入站消息消费者线程。
     *
     * <p>后台守护线程循环调用 {@link MessageBus#consumeInbound()} 拉取通道发布的入站消息，
     * 逐条交给 {@link MessageRouter#route} 处理（指令 / 系统消息 / 用户消息）。
     * 单条消息处理异常不会中断循环；bus 关闭或线程被中断时退出。
     *
     * <p>该消费者是外部通道（微信 / Telegram 等）消息进入 LLM 处理链的唯一入口：
     * 通道把消息 publish 到入站队列后，由本线程拉取并路由，回复再经出站队列回到通道。
     */
    synchronized void startInboundConsumer() {
        if (inboundConsumerThread != null) {
            return;
        }
        inboundConsumerRunning = true;
        Thread t = new Thread(this::runInboundConsumerLoop, "workflow-inbound-consumer");
        t.setDaemon(true);
        t.start();
        inboundConsumerThread = t;
        log.info("Inbound consumer started: thread=workflow-inbound-consumer");
    }

    /**
     * 停止入站消息消费者线程（中断 + 置位运行标志）。
     */
    synchronized void stopInboundConsumer() {
        inboundConsumerRunning = false;
        Thread t = inboundConsumerThread;
        if (t != null) {
            t.interrupt();
            inboundConsumerThread = null;
        }
        log.info("Inbound consumer stopped");
    }

    private void runInboundConsumerLoop() {
        while (inboundConsumerRunning) {
            InboundMessage msg;
            try {
                msg = bus.consumeInbound();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (BusClosedException e) {
                log.info("MessageBus 已关闭，入站消费者退出");
                break;
            } catch (Throwable t) {
                log.error("入站消费者拉取消息异常: error_type={}, error_message={}",
                        t.getClass().getSimpleName(), t.getMessage(), t);
                break;
            }
            if (msg == null) {
                continue;
            }
            try {
                router.route(msg);
            } catch (Exception e) {
                log.error("处理入站消息失败: channel={}, chat_id={}, sender_id={}, error_type={}, error_message={}",
                        msg.getChannel(), msg.getChatId(), msg.getSenderId(),
                        e.getClass().getSimpleName(), e.getMessage(), e);
            }
        }
    }

    // ==================== no-op 兜底实现（缺失对应插件时使用） ====================

    /**
     * 注册标准工具集到 ToolService。
     *
     * <p>tools-plugin 的 {@code ToolsPlugin.start()} 仅创建一个空的 {@code ToolRegistry}
     * 并注册为 {@link ToolService} 契约，不实例化任何具体 Tool 实现。
     * 此处在 {@code SecurityGuard} 与 {@code SkillsLoader} 就绪后补齐注册：
     * <ul>
     *   <li>{@link ExecTool} —— 共享本运行期创建的 {@link SecurityGuard}，UI 策略更新实时生效</li>
     *   <li>{@link SkillsTool} —— 共享 {@code ContextBuilder.getSkillsLoader()} 实例</li>
     *   <li>文件类工具（read/write/edit/list_dir）—— 共享同一个 {@link SecurityGuard}</li>
     *   <li>Web 工具（web_fetch/web_search）—— 独立 OkHttpClient，无外部依赖</li>
     * </ul>
     *
     * <p>注意：当 {@code tools} 是 {@link #emptyToolService()} 兜底实现时（tools-plugin 未启动），
     * {@code register} 为 no-op，工具不会被实际注册，但也不抛异常。
     *
     * @param tools     ToolService 实例（由 tools-plugin 注册或 emptyToolService 兜底）
     * @param workspace 工作空间路径
     * @param guard     共享的安全守卫实例
     */
    private void registerStandardTools(ToolService tools, String workspace, SecurityGuard guard) {
        int count = 0;
        try {
            // 命令执行：共享 SecurityGuard，UI 更新策略实时生效
            tools.register(new ExecTool(workspace, guard));
            count++;

            // 技能管理：共享 ContextBuilder 的 SkillsLoader，确保技能列表视图一致
            tools.register(new SkillsTool(workspace, contextBuilder.getSkillsLoader()));
            count++;

            // 文件类工具：共享 SecurityGuard
            tools.register(new ReadFileTool(guard));
            tools.register(new WriteFileTool(guard));
            tools.register(new EditFileTool(guard));
            tools.register(new ListDirTool(guard));
            count += 4;

            // Web 工具：独立 OkHttpClient，无外部依赖
            tools.register(new WebFetchTool(50000));
            String webSearchApiKey = config.getTools() != null && config.getTools().getWeb() != null
                    ? config.getTools().getWeb().getSearch().getApiKey()
                    : null;
            tools.register(new WebSearchTool(webSearchApiKey, 5));
            count += 2;
        } catch (Throwable t) {
            log.error("注册标准工具失败（已注册 {} 个）: error_type={}, error_message={}",
                    count, t.getClass().getSimpleName(), t.getMessage(), t);
        }
        log.info("标准工具注册完成: count={}, workspace={}", count, workspace);
    }

    /**
     * 空 {@link ToolService} 兜底实现：所有查询返回空，execute 抛异常。
     *
     * <p>当 tools-plugin 未启动或未注册 ToolService 时使用，使 workflow 能在
     * 无工具环境下启动（LLM 调用不带 tool 定义，工具调用路径不可达）。
     */
    private static ToolService emptyToolService() {
        return new ToolService() {
            @Override
            public void register(Tool tool) { /* no-op */ }
            @Override
            public void unregister(String name) { /* no-op */ }
            @Override
            public Optional<Tool> get(String name) { return Optional.empty(); }
            @Override
            public boolean hasTool(String name) { return false; }
            @Override
            public String execute(String name, Map<String, Object> args) {
                throw new IllegalStateException("ToolService 未就绪（tools-plugin 未注册），无法执行工具: " + name);
            }
            @Override
            public List<String> list() { return List.of(); }
            @Override
            public int count() { return 0; }
            @Override
            public List<ToolDefinition> getDefinitions() { return List.of(); }
            @Override
            public List<String> getSummaries() { return List.of(); }
            @Override
            public void clear() { /* no-op */ }
            @Override
            public ToolService filter(List<String> allowedToolNames) { return this; }
        };
    }

    /**
     * 空 {@link MemoryService} 兜底实现：init 为 no-op，读写均 no-op。
     *
     * <p>当 memory-plugin 未启动或未注册 MemoryService 时使用，
     * 使 workflow 能在无记忆环境下启动（记忆相关 section 输出空字符串）。
     */
    private static MemoryService emptyMemoryService() {
        return new MemoryService() {
            @Override
            public void init(String workspace) { /* no-op */ }
            @Override
            public void addEntry(String content, double importance, List<String> tags, String source) { /* no-op */ }
            @Override
            public String getMemoryContext(String currentMessage, int tokenBudget) { return ""; }
            @Override
            public String getMemoryContext() { return ""; }
        };
    }
}
