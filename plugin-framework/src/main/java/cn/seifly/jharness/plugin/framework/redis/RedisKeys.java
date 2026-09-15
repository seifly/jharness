package cn.seifly.jharness.plugin.framework.redis;

/**
 * Redis 键命名空间常量。
 *
 * <p>统一管理所有插件在 Redis 中的键前缀，避免冲突。
 * 格式约定：{@code plugin_id:section[:sub_key]}
 */
public final class RedisKeys {

    private RedisKeys() {
    }

    // ==================== models-plugin ====================
    /**
     * 当前激活的 Provider ID
     */
    public static final String MODELS_CURRENT_PROVIDER = "models:current_provider";
    /**
     * Provider 配置 JSON
     */
    public static final String MODELS_PROVIDERS = "models:providers";
    /**
     * 可用模型列表 JSON
     */
    public static final String MODELS_AVAILABLE = "models:available";

    // ==================== sessions-plugin ====================
    /**
     * 会话列表命名空间，key: sessions:session:{sessionKey}
     */
    public static final String SESSIONS_NAMESPACE = "sessions:session:";
    /**
     * 所有会话键集合
     */
    public static final String SESSIONS_KEYS = "sessions:keys";

    // ==================== skills-plugin ====================
    /**
     * 已安装技能列表 JSON
     */
    public static final String SKILLS_INSTALLED = "skills:installed";
    /**
     * 可用技能注册表 JSON
     */
    public static final String SKILLS_REGISTRY = "skills:registry";

    // ==================== memory-plugin ====================
    /**
     * 记忆条目命名空间，key: memory:entry:{id}
     */
    public static final String MEMORY_NAMESPACE = "memory:entry:";
    /**
     * 记忆索引（所有条目 ID 列表）
     */
    public static final String MEMORY_INDEX = "memory:index";

    // ==================== tools-plugin ====================
    /**
     * 已注册工具列表 JSON
     */
    public static final String TOOLS_REGISTRY = "tools:registry";
    /**
     * Token 用量统计 JSON
     */
    public static final String TOOLS_TOKEN_STATS = "tools:token_stats";

    // ==================== workflow-plugin ====================
    /**
     * Agent 运行配置 JSON（model、temperature、maxTokens 等）
     */
    public static final String WORKFLOW_AGENT_CONFIG = "workflow:agent_config";
    /**
     * 当前任务运行状态
     */
    public static final String WORKFLOW_TASK_STATUS = "workflow:task_status";
    /**
     * 协同记录命名空间
     */
    public static final String WORKFLOW_COLLAB_NAMESPACE = "workflow:collab:";

    // ==================== channel-plugin ====================
    /**
     * 通道配置 JSON
     */
    public static final String NOTIFICATIONS_CHANNELS = "notifications:channels";
    /**
     * 通道状态命名空间
     */
    public static final String NOTIFICATIONS_STATUS_NAMESPACE = "notifications:status:";
    /**
     * 微信通道配置 JSON（resumeContext 等）
     */
    public static final String NOTIFICATIONS_WECHAT_CONFIG = "notifications:wechat:config";

    // ==================== cron-plugin ====================
    /**
     * Cron 任务列表 JSON
     */
    public static final String CRON_JOBS = "cron:jobs";

    // ==================== mcp-plugin ====================
    /**
     * MCP 服务器配置 JSON
     */
    public static final String MCP_SERVERS = "mcp:servers";

    // ==================== evolution-plugin ====================
    /**
     * 反馈记录命名空间
     */
    public static final String EVOLUTION_FEEDBACK_NAMESPACE = "evolution:feedback:";
    /**
     * 变体管理 JSON
     */
    public static final String EVOLUTION_VARIANTS = "evolution:variants";

    // ==================== heartbeat-plugin ====================
    /**
     * 心跳状态 JSON
     */
    public static final String HEARTBEAT_STATUS = "heartbeat:status";

    // ==================== ui-plugin ====================
    /**
     * Web Console 配置（CORS、认证等网关配置）
     */
    public static final String UI_GATEWAY_CONFIG = "ui:gateway_config";

    public static final String SOCIAL_NETWORK_CONFIG = "social_network_config";

}
