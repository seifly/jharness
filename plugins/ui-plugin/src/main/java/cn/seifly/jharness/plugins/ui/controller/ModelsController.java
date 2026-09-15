package cn.seifly.jharness.plugins.ui.controller;

import cn.seifly.jharness.plugin.framework.config.AgentConfig;
import cn.seifly.jharness.plugin.framework.config.ModelsConfig;
import cn.seifly.jharness.plugin.framework.config.ProvidersConfig;
import cn.seifly.jharness.plugin.framework.redis.ConfigStore;
import cn.seifly.jharness.plugin.framework.redis.RedisKeys;
import cn.seifly.jharness.plugin.framework.redis.RedisShared;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型列表 API 控制器
 *
 * 提供所有模型定义的查询功能。
 *
 * <p>迁移说明：模型定义从 Redis 的 {@link RedisKeys#MODELS_AVAILABLE} 读取
 * （结构 {@code Map<modelName, {provider, model, maxContextSize, description}>}）；
 * authorized 字段依据 {@link RedisKeys#MODELS_PROVIDERS} 中对应 Provider 是否配置 apiKey 判断。
 */
@RestController
@RequestMapping("/api/models")
@CrossOrigin(origins = "${jclaw.gateway.cors-origin:*}", allowedHeaders = "*", methods = {RequestMethod.GET, RequestMethod.OPTIONS})
@Slf4j
public class ModelsController {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final ConfigStore store = RedisShared.store();

    /**
     * 获取所有模型定义列表
     *
     * 每个模型节点会一并附带 authorized 字段，表明对应 Provider 是否已配置 API Key。
     *
     * @return 模型列表
     */
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> getModels() {
        Map<String, Map<String, Object>> models = getModelsMap();
        Map<String, Map<String, Object>> providers = getProvidersMap();

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> entry : models.entrySet()) {
            String modelName = entry.getKey();
            Map<String, Object> def = entry.getValue();
            String providerName = def.get("provider") != null ? def.get("provider").toString() : "";

            Map<String, Object> provider = providers.get(providerName);
            boolean authorized = provider != null
                    && provider.get("apiKey") != null
                    && !provider.get("apiKey").toString().isEmpty();

            Map<String, Object> modelNode = new HashMap<>();
            modelNode.put("name", modelName);
            modelNode.put("provider", providerName);
            modelNode.put("model", def.getOrDefault("model", ""));
            modelNode.put("maxContextSize", def.getOrDefault("maxContextSize", 0));
            modelNode.put("description", def.getOrDefault("description", ""));
            modelNode.put("authorized", authorized);
            result.add(modelNode);
        }

        return ResponseEntity.ok(result);
    }

    // ==================== Redis 读取 ====================

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> getModelsMap() {
        ModelsConfig modelsConfig = store.getJSON(RedisKeys.MODELS_AVAILABLE, ModelsConfig.class);
        Map<String, ModelsConfig.ModelDefinition> models = modelsConfig.getDefinitions();//models1.get("definitions");
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for(Map.Entry<String, ModelsConfig.ModelDefinition> entry : models.entrySet()){
            result.put(entry.getKey(), MAPPER.convertValue(entry.getValue(), new TypeReference<Map<String, Object>>() {
            }));
        }
        return result != null ? result : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> getProvidersMap() {
        ProvidersConfig providersConfig = store.getJSON(RedisKeys.MODELS_PROVIDERS, ProvidersConfig.class);
        Map<String, Map<String, Object>> providers = MAPPER.convertValue(providersConfig, new TypeReference<Map<String, Map<String, Object>>>() {
        });
        return providers != null ? providers : new LinkedHashMap();
    }
}
