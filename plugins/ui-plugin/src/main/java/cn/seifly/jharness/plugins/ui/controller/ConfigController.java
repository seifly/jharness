package cn.seifly.jharness.plugins.ui.controller;

import cn.seifly.jharness.plugin.framework.redis.ConfigStore;
import cn.seifly.jharness.plugin.framework.redis.RedisKeys;
import cn.seifly.jharness.plugin.framework.redis.RedisShared;
import cn.seifly.jharness.plugin.framework.security.SecurityService;
import cn.seifly.jharness.plugin.framework.service.AgentRuntimeService;
import cn.seifly.jharness.plugin.framework.service.ServiceRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 配置管理 API 控制器
 *
 * 提供 Agent 及模型配置的查询和更新功能。
 *
 * <p>迁移说明：Agent 运行配置从 Redis 的 {@link RedisKeys#WORKFLOW_AGENT_CONFIG} 读写，
 * 不再注入跨插件 {@code Config} / {@code AgentRuntime} / {@code SecurityGuard}。
 * 模型切换后的热重载通过 {@link #runtime()} 取 {@link AgentRuntimeService} 触发；
 * 安全策略（restrictToWorkspace / commandBlacklist）的实时下发通过
 * {@link #security()} 取 {@link SecurityService} 调用（#4 契约），
 * 由 workflow-plugin 注册实现，将更新转发到其持有的 {@code SecurityGuard} 实例。
 */
@RestController
@RequestMapping("/api/config")
@CrossOrigin(origins = "${jclaw.gateway.cors-origin:*}", allowedHeaders = "*", methods = {RequestMethod.GET, RequestMethod.PUT, RequestMethod.OPTIONS})
@Slf4j
public class ConfigController {

    private final ConfigStore store = RedisShared.store();

    @Autowired
    private ServiceRegistry services;

    /**
     * 获取已注册的 AgentRuntimeService（由 workflow-plugin 在 start() 时注册）；
     * workflow-plugin 未启动时返回 null，调用方跳过热重载。
     */
    private AgentRuntimeService runtime() {
        return services.get(AgentRuntimeService.class).orElse(null);
    }

    /**
     * 获取已注册的 SecurityService（由 workflow-plugin 在 start() 时注册）；
     * workflow-plugin 未启动时返回 null，调用方跳过安全策略下发。
     */
    private SecurityService security() {
        return services.get(SecurityService.class).orElse(null);
    }

    /**
     * 把 restrictToWorkspace 的请求值（可能是 Boolean 或 String）归一化为 boolean。
     */
    private static boolean toBoolean(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value == null) {
            return false;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    /**
     * 读取当前模型与 Provider 配置
     *
     * @return 包含 model 和 provider 的配置
     */
    @GetMapping("/model")
    public ResponseEntity<Map<String, Object>> getModelConfig() {
        Map<String, Object> agentConfig = getAgentConfigMap();

        Map<String, Object> result = new HashMap<>();
        result.put("model", agentConfig.getOrDefault("model", ""));

        String savedProvider = (String) agentConfig.get("provider");
        String currentProvider = (savedProvider != null && !savedProvider.isEmpty())
                ? savedProvider : firstAuthorizedProvider();
        result.put("provider", currentProvider);

        return ResponseEntity.ok(result);
    }

    /**
     * 更新模型与 Provider 配置并持久化
     *
     * @param request 包含 model 和 provider 的请求体
     * @return 更新结果
     */
    @PutMapping("/model")
    public ResponseEntity<Map<String, Object>> updateModelConfig(@RequestBody Map<String, Object> request) {
        Map<String, Object> agentConfig = getAgentConfigMap();

        if (request.containsKey("model")) {
            String newModel = (String) request.get("model");
            agentConfig.put("model", newModel);

            // 切换 model 时，自动从 models:available 同步对应的 provider，
            // 避免 provider 与 model 手动错配（如 qwen3-max 被发到智谱的 api_base）
            Map<String, Map<String, Object>> models = getModelsMap();
            Map<String, Object> modelDef = models.get(newModel);
            if (modelDef != null && modelDef.get("provider") != null) {
                agentConfig.put("provider", modelDef.get("provider"));
            }
        }

        // 允许显式覆盖 provider（优先级高于 model 自动推断，适用于自定义场景）
        if (request.containsKey("provider")) {
            agentConfig.put("provider", request.get("provider"));
        }

        saveAgentConfigMap(agentConfig);

        // 通知 workflow-plugin 把 Redis 中的 model / provider 同步进内存 Config 并触发 Provider 重建
        AgentRuntimeService rt = runtime();
        if (rt != null) {
            boolean reloaded = rt.reloadModel();
            log.info("模型热重载: reloaded={}", reloaded);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "Model updated");

        return ResponseEntity.ok(result);
    }

    /**
     * 读取 Agent 全量配置项
     *
     * @return Agent 配置
     */
    @GetMapping("/agent")
    public ResponseEntity<Map<String, Object>> getAgentConfig() {
        Map<String, Object> agentConfig = getAgentConfigMap();

        Map<String, Object> result = new HashMap<>();
        result.put("workspace", agentConfig.getOrDefault("workspace", ""));
        result.put("model", agentConfig.getOrDefault("model", ""));
        result.put("maxTokens", agentConfig.getOrDefault("maxTokens", 0));
        result.put("temperature", agentConfig.getOrDefault("temperature", 0.0));
        result.put("maxToolIterations", agentConfig.getOrDefault("maxToolIterations", 0));
        result.put("heartbeatEnabled", agentConfig.getOrDefault("heartbeatEnabled", false));
        result.put("restrictToWorkspace", agentConfig.getOrDefault("restrictToWorkspace", false));
        result.put("commandBlacklist", agentConfig.getOrDefault("commandBlacklist", new ArrayList<>()));

        return ResponseEntity.ok(result);
    }

    /**
     * 更新 Agent 配置并持久化
     *
     * @param request 包含更新字段的请求体
     * @return 更新结果
     */
    @PutMapping("/agent")
    public ResponseEntity<Map<String, Object>> updateAgentConfig(@RequestBody Map<String, Object> request) {
        Map<String, Object> agentConfig = getAgentConfigMap();

        if (request.containsKey("model")) {
            agentConfig.put("model", request.get("model"));
        }
        if (request.containsKey("maxTokens")) {
            agentConfig.put("maxTokens", request.get("maxTokens"));
        }
        if (request.containsKey("temperature")) {
            agentConfig.put("temperature", ((Number) request.get("temperature")).doubleValue());
        }
        if (request.containsKey("maxToolIterations")) {
            agentConfig.put("maxToolIterations", request.get("maxToolIterations"));
        }
        if (request.containsKey("heartbeatEnabled")) {
            agentConfig.put("heartbeatEnabled", request.get("heartbeatEnabled"));
        }
        if (request.containsKey("restrictToWorkspace")) {
            agentConfig.put("restrictToWorkspace", request.get("restrictToWorkspace"));
            // #4 通过 SecurityService 契约把 restrictToWorkspace 实时下发到 workflow 内的 SecurityGuard
            boolean restrict = toBoolean(request.get("restrictToWorkspace"));
            SecurityService sec = security();
            if (sec != null) {
                sec.updateRestrictToWorkspace(restrict);
            }
        }
        if (request.containsKey("commandBlacklist")) {
            @SuppressWarnings("unchecked")
            List<String> blacklist = (List<String>) request.get("commandBlacklist");
            agentConfig.put("commandBlacklist", blacklist != null ? blacklist : new ArrayList<>());
            // #4 通过 SecurityService 契约把 commandBlacklist 实时下发到 workflow 内的 SecurityGuard
            SecurityService sec = security();
            if (sec != null) {
                sec.updateCommandBlacklist(blacklist);
            }
        }

        saveAgentConfigMap(agentConfig);

        // 若更新涉及 model，同样触发 workflow-plugin 的 Provider 热重载
        if (request.containsKey("model")) {
            AgentRuntimeService rt = runtime();
            if (rt != null) {
                rt.reloadModel();
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "Agent config updated");

        return ResponseEntity.ok(result);
    }

    // ==================== Redis 配置读写 ====================

    @SuppressWarnings("unchecked")
    private Map<String, Object> getAgentConfigMap() {
        Map<String, Object> cfg = store.getJSON(RedisKeys.WORKFLOW_AGENT_CONFIG, Map.class);
        return cfg != null ? cfg : new LinkedHashMap<>();
    }

    private void saveAgentConfigMap(Map<String, Object> agentConfig) {
        store.putJSON(RedisKeys.WORKFLOW_AGENT_CONFIG, agentConfig);
    }

    /**
     * 读取可用模型定义（models:available），用于切换 model 时自动推断 provider。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> getModelsMap() {
        Map<String, Map<String, Object>> models = store.getJSON(RedisKeys.MODELS_AVAILABLE, Map.class);
        return models != null ? models : java.util.Collections.emptyMap();
    }

    /**
     * 读取 models:providers 中第一个已配置 apiKey 的 Provider 名（原 ProvidersController.getCurrentProvider）。
     * 无法判断时返回空串。
     */
    private String firstAuthorizedProvider() {
        Map<String, Map<String, Object>> providers = getProvidersMap();
        String[] order = {"openrouter", "dashscope", "zhipu", "openai", "anthropic", "gemini", "ollama"};
        for (String name : order) {
            Map<String, Object> p = providers.get(name);
            if (p != null) {
                Object apiKey = p.get("apiKey");
                if (apiKey != null && !apiKey.toString().isEmpty()) {
                    return name;
                }
            }
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> getProvidersMap() {
        Map<String, Map<String, Object>> providers = store.getJSON(RedisKeys.MODELS_PROVIDERS, Map.class);
        return providers != null ? providers : java.util.Collections.emptyMap();
    }
}
