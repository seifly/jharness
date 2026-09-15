package cn.seifly.jharness.plugins.workflow;

import cn.seifly.jharness.plugin.framework.llm.LlmService;
import cn.seifly.jharness.plugins.sessions.SessionManager;
import cn.seifly.jharness.plugin.framework.tools.ToolService;

import java.nio.file.Paths;
import java.util.UUID;

import lombok.Getter;
import lombok.Setter;

/**
 * Agent 执行上下文
 * 封装 LLM 调用所需的基础依赖，避免在方法间反复传递多个参数。
 *
 * <p>持有一个共享的 {@link SessionManager}，供所有 {@link RoleAgent} 复用，
 * 避免每个 RoleAgent 独立初始化 SessionManager 带来的重复磁盘 IO 开销。
 */
@Getter
public class ExecutionContext {

    private final LlmService provider;
    private final ToolService tools;
    private final String workspace;
    private final String model;
    private final int maxIterations;

    /** 共享会话管理器（协同场景下所有 RoleAgent 复用同一实例） */
    private final SessionManager sharedSessionManager;

    /** 协同会话 ID（使用 UUID 短格式，用于日志关联和调试） */
    private final String sessionId;

    /** 基础系统提示词（可选，用于传递主 Agent 的核心身份信息） */
    @Setter
    private String baseSystemPrompt;

    /** RoleAgent 序号计数器（用于生成唯一的协同会话内序号） */
    private int agentSequence = 0;

    public ExecutionContext(LlmService provider, ToolService tools,
                            String workspace, String model, int maxIterations) {
        this.provider = provider;
        this.tools = tools;
        this.workspace = workspace;
        this.model = model;
        this.maxIterations = maxIterations;
        String sessionPath = Paths.get(workspace, "sessions", "collaboration").toString();
        this.sharedSessionManager = new SessionManager(sessionPath);
        this.sessionId = generateShortUUID();
    }

    /**
     * 生成短格式 UUID（取前8位）
     */
    private String generateShortUUID() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * 工厂方法：统一创建 RoleAgent
     * 确保所有 RoleAgent 都通过此方法创建，便于统一管理和追踪
     *
     * @param role Agent 角色
     * @return 新创建的 RoleAgent
     */
    public RoleAgent createAgentExecutor(AgentRole role) {
        return new RoleAgent(role,
                provider,
                tools,
                sharedSessionManager,
                model,
                maxIterations,
                sessionId,
                ++agentSequence,
                baseSystemPrompt);
    }
}
