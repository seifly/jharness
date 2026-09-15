package cn.seifly.jharness.plugins.ui.controller;

import cn.seifly.jharness.plugin.framework.web.ApiResult;
import cn.seifly.jharness.plugin.framework.redis.ConfigStore;
import cn.seifly.jharness.plugin.framework.redis.RedisKeys;
import cn.seifly.jharness.plugin.framework.redis.RedisShared;
import cn.seifly.jharness.plugins.ui.WebUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM 提供商 API 控制器
 *
 * 提供 LLM 提供商配置的查询和更新功能。
 *
 * <p>迁移说明：Provider 配置从 Redis 的 {@link RedisKeys#MODELS_PROVIDERS} 读写，
 * 不再注入跨插件 {@code Config}。结构为 {@code Map<providerName, {apiKey, apiBase}>}。
 */
@RestController
@RequestMapping("/api/providers")
@CrossOrigin(origins = "${jclaw.gateway.cors-origin:*}", allowedHeaders = "*", methods = {RequestMethod.GET, RequestMethod.PUT, RequestMethod.OPTIONS})
@Slf4j
public class ProvidersController {

    /** 已知 Provider 名称及默认 API Base（取自原 ProvidersConfig.getDefaultApiBase） */
    private static final Map<String, String> DEFAULT_API_BASES = new HashMap<>();

    static {
        DEFAULT_API_BASES.put("openrouter", "https://openrouter.ai/api/v1");
        DEFAULT_API_BASES.put("openai", "https://api.openai.com/v1");
        DEFAULT_API_BASES.put("anthropic", "https://api.anthropic.com");
        DEFAULT_API_BASES.put("zhipu", "https://open.bigmodel.cn/api/paas/v4");
        DEFAULT_API_BASES.put("dashscope", "https://dashscope.aliyuncs.com/compatible-mode/v1");
        DEFAULT_API_BASES.put("gemini", "https://generativelanguage.googleapis.com/v1beta");
        DEFAULT_API_BASES.put("ollama", "http://localhost:11434");
    }

    /** Provider 优先级顺序（原 getCurrentProvider） */
    private static final String[] PROVIDER_PRIORITY = {
            "openrouter", "dashscope", "zhipu", "openai", "anthropic", "gemini", "ollama"
    };

    private final ConfigStore store = RedisShared.store();

    /**
     * 获取所有 LLM 提供商配置列表
     *
     * @return 提供商列表，包含 name、apiBase、掩码的 apiKey 以及 authorized 字段
     */
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> getProviders() {
        Map<String, Map<String, Object>> providers = getProvidersMap();
        List<Map<String, Object>> result = new ArrayList<>();

        for (String name : PROVIDER_PRIORITY) {
            Map<String, Object> provider = new HashMap<>();
            Map<String, Object> pc = providers.get(name);
            String apiBase = pc != null ? (String) pc.getOrDefault("apiBase", defaultApiBase(name)) : defaultApiBase(name);
            String apiKey = pc != null ? (String) pc.getOrDefault("apiKey", "") : "";

            provider.put("name", name);
            provider.put("apiBase", apiBase);
            provider.put("apiKey", WebUtils.maskSecret(apiKey));
            provider.put("authorized", isValidProvider(pc));
            result.add(provider);
        }

        return ResponseEntity.ok(result);
    }

    /**
     * 更新指定 LLM 提供商的配置
     *
     * @param name 提供商名称
     * @param request 包含更新字段（apiKey、apiBase）的请求体
     * @return 更新结果
     */
    @PutMapping("/{name}")
    public ResponseEntity<Map<String, Object>> updateProvider(
            @PathVariable("name") String name,
            @RequestBody Map<String, Object> request) {

        if (!DEFAULT_API_BASES.containsKey(name)) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Update failed");
            return ResponseEntity.status(400).body(error);
        }

        Map<String, Map<String, Object>> providers = getProvidersMap();
        Map<String, Object> provider = providers.computeIfAbsent(name, k -> new LinkedHashMap<>());

        if (request.containsKey("apiKey")) {
            String apiKey = (String) request.get("apiKey");
            if (!WebUtils.isSecretMasked(apiKey)) {
                provider.put("apiKey", apiKey);
            }
        }

        if (request.containsKey("apiBase")) {
            provider.put("apiBase", request.get("apiBase"));
        }

        saveProvidersMap(providers);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "Provider updated");

        return ResponseEntity.ok(result);
    }

    // ==================== 公共方法（供其他 Controller 使用）====================

    /**
     * 根据名称获取 Provider 配置 Map（apiKey、apiBase）。
     */
    public ApiResult<Map<String, Object>> getProviderByName(String name) {
        Map<String, Object> provider = getProvidersMap().get(name);
        return provider != null ? ApiResult.ok(provider) : ApiResult.error(404, "Provider not found");
    }

    /**
     * 获取当前第一个有效 Provider 名称（按优先级）。
     */
    public String getCurrentProvider() {
        Map<String, Map<String, Object>> providers = getProvidersMap();
        for (String name : PROVIDER_PRIORITY) {
            if (isValidProvider(providers.get(name))) {
                return name;
            }
        }
        return "";
    }

    // ==================== 私有辅助方法 ====================

    private boolean isValidProvider(Map<String, Object> provider) {
        if (provider == null) return false;
        Object apiKey = provider.get("apiKey");
        return apiKey != null && !apiKey.toString().isEmpty();
    }

    private String defaultApiBase(String name) {
        return DEFAULT_API_BASES.getOrDefault(name, "");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> getProvidersMap() {
        Map<String, Map<String, Object>> providers = store.getJSON(RedisKeys.MODELS_PROVIDERS, Map.class);
        return providers != null ? providers : new LinkedHashMap<>();
    }

    private void saveProvidersMap(Map<String, Map<String, Object>> providers) {
        store.putJSON(RedisKeys.MODELS_PROVIDERS, providers);
    }
}
