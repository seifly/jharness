package cn.seifly.jharness.plugins.workflow;

import cn.seifly.jharness.plugin.framework.bus.InboundMessage;
import cn.seifly.jharness.plugin.framework.redis.ConfigStore;
import cn.seifly.jharness.plugin.framework.redis.RedisKeys;
import cn.seifly.jharness.plugin.framework.redis.RedisShared;
import cn.seifly.jharness.plugin.framework.service.AgentRuntimeService;
import cn.seifly.jharness.plugin.framework.service.StreamCallback;
import cn.seifly.jharness.plugin.framework.config.Config;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@link AgentRuntimeService} 的 workflow-plugin 实现。
 *
 * <p>把 SDK 契约适配到本插件内部的 {@link MessageRouter} / {@link ProviderManager}：
 * <ul>
 *   <li>{@link #processDirect} / {@link #processDirectStream}：通过 {@link MessageRouter#route}
 *       / {@link MessageRouter#routeUserStream} 复用现有 ReAct 执行链；channel 固定为
 *       {@code "cli"}，避免 {@code publishReplyIfNeeded} 把回复再次发到出站 bus；</li>
 *   <li>{@link #abortCurrentTask} / {@link #isTaskRunning}：透传到 {@code ReActExecutor}；</li>
 *   <li>{@link #reloadModel}：先把 Redis 中 ui-plugin 写入的 agent 配置同步进内存 {@link Config}，
 *       再触发 {@link ProviderManager#reloadModel}，使 ui 端模型切换立即生效。</li>
 * </ul>
 *
 * <p>该类不使用 {@code @Extension}：Service 路径与 PF4J ExtensionPoint 是平行的能力发现机制，
 * Service 由插件自行 {@code register/unregister}，无需 PluginManager 扫描。
 */
@Slf4j
class AgentRuntimeServiceImpl implements AgentRuntimeService {

    /** channel=cli 保证路由器走直接调用路径，不向出站 bus 二次发布回复 */
    private static final String CHANNEL_CLI = "cli";
    private static final String SENDER_WEB = "web";

    private final WorkflowRuntime runtime;

    AgentRuntimeServiceImpl(WorkflowRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public String processDirect(String content, String sessionKey, List<String> images) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        String key = resolveSessionKey(sessionKey);
        try {
            InboundMessage msg = new InboundMessage(CHANNEL_CLI, SENDER_WEB, key, content);
            msg.setSessionKey(key);
            // #3 多模态：images 非空时跳过指令/系统分发，直接走 routeUser 的 buildContextWithImages 分支，
            // 使 LLM 能识别图片内容；纯文本仍走 route() 保留指令/系统消息分发能力
            if (images != null && !images.isEmpty()) {
                return runtime.router.routeUser(msg, images);
            }
            return runtime.router.route(msg);
        } catch (Exception e) {
            log.error("processDirect 失败: session_key={}, error_type={}, error_message={}",
                    key, e.getClass().getSimpleName(), e.getMessage(), e);
            return "⚠️ 处理失败：" + e.getMessage();
        }
    }

    @Override
    public void processDirectStream(String content, String sessionKey,
                                    List<String> images, StreamCallback callback) {
        if (callback == null) {
            log.warn("processDirectStream 收到 null callback，跳过执行");
            return;
        }
        if (content == null || content.isEmpty()) {
            callback.onComplete("");
            return;
        }
        String key = resolveSessionKey(sessionKey);
        InboundMessage msg = new InboundMessage(CHANNEL_CLI, SENDER_WEB, key, content);
        msg.setSessionKey(key);
        try {
            // #3 多模态：images 非空时走 routeUserStream 的 buildContextWithImages 分支，
            // routeUserStream 内部已完成 onComplete / onError 回调；
            // 此处 try/catch 仅做兜底，防止异常逃逸到调用方线程
            runtime.router.routeUserStream(msg, images, callback);
        } catch (Throwable t) {
            log.error("processDirectStream 异常: session_key={}, error_type={}, error_message={}",
                    key, t.getClass().getSimpleName(), t.getMessage(), t);
            try {
                callback.onError(t);
            } catch (Throwable inner) {
                log.warn("callback.onError 自身抛出异常，已忽略: {}", inner.toString());
            }
        }
    }

    @Override
    public boolean abortCurrentTask() {
        ProviderComponents comps = runtime.providerManager.getComponents();
        if (comps == null || comps.reActExecutor == null) {
            return false;
        }
        if (!comps.reActExecutor.isRunning()) {
            return false;
        }
        comps.reActExecutor.abort();
        log.info("abortCurrentTask: 中断信号已发送");
        return true;
    }

    @Override
    public boolean isTaskRunning() {
        ProviderComponents comps = runtime.providerManager.getComponents();
        return comps != null && comps.reActExecutor != null && comps.reActExecutor.isRunning();
    }

    @Override
    public boolean reloadModel() {
        // 1. 把 Redis 中 ui-plugin 写入的 agent 配置同步进内存 Config
        syncAgentConfigFromRedis();
        // 2. 触发 Provider 重建（依据 config.agent.model / provider）
        try {
            return runtime.providerManager.reloadModel();
        } catch (Exception e) {
            log.error("reloadModel 异常: error_type={}, error_message={}",
                    e.getClass().getSimpleName(), e.getMessage(), e);
            return false;
        }
    }

    /**
     * 把 Redis 中 ui-plugin 写入的 agent 配置（model / provider）同步进内存 Config。
     *
     * <p>ui-plugin 的 ConfigController 把全量 agent 配置以 Map 形式写入
     * {@link RedisKeys#WORKFLOW_AGENT_CONFIG}；此处仅取运行期 Provider 选择所需的
     * {@code model} / {@code provider} 字段，其它字段（maxTokens 等）由各组件各自读取。
     */
    @SuppressWarnings("unchecked")
    private void syncAgentConfigFromRedis() {
        try {
            ConfigStore store = RedisShared.store();
            Map<String, Object> agent = store.getJSON(RedisKeys.WORKFLOW_AGENT_CONFIG, Map.class);
            if (agent == null || agent.isEmpty()) {
                return;
            }
            Object model = agent.get("model");
            if (model instanceof String m && !m.isEmpty()) {
                runtime.config.getAgent().setModel(m);
            }
            Object provider = agent.get("provider");
            if (provider instanceof String p && !p.isEmpty()) {
                runtime.config.getAgent().setProvider(p);
            }
            log.info("Agent 配置已从 Redis 同步: model={}, provider={}",
                    runtime.config.getAgent().getModel(),
                    runtime.config.getAgent().getProvider());
        } catch (Exception e) {
            log.warn("从 Redis 同步 agent 配置失败，忽略: error={}", e.getMessage());
        }
    }

    private static String resolveSessionKey(String sessionKey) {
        if (sessionKey == null || sessionKey.isEmpty()) {
            return CHANNEL_CLI + ":" + UUID.randomUUID();
        }
        return sessionKey;
    }
}
