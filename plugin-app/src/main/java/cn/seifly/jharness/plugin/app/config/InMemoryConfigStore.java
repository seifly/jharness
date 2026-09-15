package cn.seifly.jharness.plugin.app.config;

import cn.seifly.jharness.plugin.framework.config.Config;
import cn.seifly.jharness.plugin.framework.config.AgentConfig;
import cn.seifly.jharness.plugin.framework.config.ChannelsConfig;
import cn.seifly.jharness.plugin.framework.config.GatewayConfig;
import cn.seifly.jharness.plugin.framework.config.MCPServersConfig;
import cn.seifly.jharness.plugin.framework.config.ModelsConfig;
import cn.seifly.jharness.plugin.framework.config.ProvidersConfig;
import cn.seifly.jharness.plugin.framework.config.SocialNetworkConfig;
import cn.seifly.jharness.plugin.framework.config.ToolsConfig;
import cn.seifly.jharness.plugin.framework.redis.ConfigStore;
import cn.seifly.jharness.plugin.framework.redis.RedisKeys;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 基于进程内 {@link ConcurrentHashMap} 的 {@link ConfigStore} 实现。
 *
 * <p>用途：开发期 / 未部署 Redis 时的默认实现，保证插件控制器能正常构造与注册，
 * 不再因 {@code RedisShared 未初始化} 阻断启动。
 *
 * <p>局限：仅单实例内存，进程重启数据丢失；多实例部署需替换为 Redis 实现。
 */
@Slf4j
public class InMemoryConfigStore implements ConfigStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static Config config;

    private final Map<String, Object> store = new ConcurrentHashMap<>();

    public InMemoryConfigStore() throws IOException {
        config = ConfigLoader.load();
        store.put(RedisKeys.MODELS_AVAILABLE, config.getModels());
        store.put(RedisKeys.MCP_SERVERS, config.getMcpServers());
        store.put(RedisKeys.WORKFLOW_AGENT_CONFIG, config.getAgent());
        store.put(RedisKeys.NOTIFICATIONS_CHANNELS, config.getChannels());
        store.put(RedisKeys.MODELS_PROVIDERS, config.getProviders());
        store.put(RedisKeys.UI_GATEWAY_CONFIG, config.getGateway());
        store.put(RedisKeys.SKILLS_INSTALLED, config.getTools());
        store.put(RedisKeys.SOCIAL_NETWORK_CONFIG, config.getSocialNetwork());
    }
    @Override
    public Config getConfig() {
        return config;
    }

    @Override
    public String get(String key) {
        Object value = store.get(key);
        if (value == null) {
            return null;
        }
        // String 值（put/putJSON 写入的 JSON 字符串）原样返回，避免 writeValueAsString 再包一层引号
        // 造成双重编码，导致 getJSON 反序列化目标类型失败。
        if (value instanceof String s) {
            return s;
        }
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            log.error("序列化配置失败: key={}", key, e);
            return null;
        }
    }

    @Override
    public <T> T getJSON(String key, Class<T> type) {
        try {
            String json = get(key);
            if (json == null) {
                return null;
            }
            return MAPPER.readValue(json, type);
        } catch (JsonProcessingException e) {
            log.error("反序列化配置失败: key={}, type={}", key, type.getSimpleName(), e);
            return null;
        }
    }

    @Override
    public void put(String key, String value) {
        store.put(key, value);
    }

    @Override
    public void putJSON(String key, Object value) {
        try {
            put(key, MAPPER.writeValueAsString(value));
        } catch (JsonProcessingException e) {
            log.error("序列化配置失败: key={}", key, e);
        }
    }

    @Override
    public boolean exists(String key) {
        return store.containsKey(key);
    }

    @Override
    public void delete(String key) {
        store.remove(key);
    }

    @Override
    public Set<String> keys(String namespace) {
        return store.keySet().stream()
                .filter(k -> k.startsWith(namespace))
                .collect(Collectors.toSet());
    }

    // ==================== 关闭时持久化 ====================

    /**
     * 把内存中的配置持久化到配置文件（默认 ~/.jclaw/config.json）。
     *
     * <p>开发期使用内存版 ConfigStore，进程重启数据丢失；本方法在应用关闭时
     * 将各配置段（models / mcpServers / agent / channels / providers / gateway /
     * tools / socialNetwork）重新组装为 {@link Config} 写回文件，使下次启动能恢复
     * 运行期修改过的配置（如微信通道的 resumeContext）。
     *
     * <p>读取兼容两种存储形态：构造期种入的强类型对象、运行期 put/putJSON 写入的 JSON 字符串
     * （含双重编码场景，会自动剥一层再解析）。
     */
    public void saveToFile() {
        String path = ConfigLoader.getConfigPath();
        try {
            Config toSave = new Config();
            toSave.setModels(read(RedisKeys.MODELS_AVAILABLE, ModelsConfig.class));
            // MCP：运行期 ui-plugin 写入 MCP_SERVERS，构造期种入 MCP_SERVERS_CONFIG；优先运行期值
            MCPServersConfig mcp = read(RedisKeys.MCP_SERVERS, MCPServersConfig.class);
            if (mcp == null) {
                mcp = read(RedisKeys.MCP_SERVERS, MCPServersConfig.class);
            }
            toSave.setMcpServers(mcp);
            toSave.setAgent(read(RedisKeys.WORKFLOW_AGENT_CONFIG, AgentConfig.class));

            ChannelsConfig channels = read(RedisKeys.NOTIFICATIONS_CHANNELS, ChannelsConfig.class);
            // 微信 resumeContext 单独存储，合并回 channels.wechat.resumeContextJson
            if (channels != null) {
                ChannelsConfig.WechatConfig wc = read(RedisKeys.NOTIFICATIONS_WECHAT_CONFIG, ChannelsConfig.WechatConfig.class);
                if (wc != null && wc.getResumeContextJson() != null && !wc.getResumeContextJson().isEmpty()) {
                    if (channels.getWechat() != null) {
                        channels.getWechat().setResumeContextJson(wc.getResumeContextJson());
                    }
                }
            }
            toSave.setChannels(channels);

            // Providers：运行期 ui-plugin 写入 MODELS_PROVIDERS，构造期种入 PROVIDERS_CONFIG；优先运行期值
            ProvidersConfig providers = read(RedisKeys.MODELS_PROVIDERS, ProvidersConfig.class);
            if (providers == null) {
                providers = read(RedisKeys.MODELS_PROVIDERS, ProvidersConfig.class);
            }
            toSave.setProviders(providers);
            toSave.setGateway(read(RedisKeys.UI_GATEWAY_CONFIG, GatewayConfig.class));
            toSave.setTools(read(RedisKeys.SKILLS_INSTALLED, ToolsConfig.class));
            toSave.setSocialNetwork(read(RedisKeys.SOCIAL_NETWORK_CONFIG, SocialNetworkConfig.class));

            ConfigLoader.save(path, toSave);
            log.info("内存配置已持久化到文件: path={}", path);
        } catch (Exception e) {
            log.error("持久化内存配置到文件失败: path={}, error={}", path, e.getMessage(), e);
        }
    }

    /**
     * 从内存存储读取并转换为指定类型，兼容强类型对象与 JSON 字符串（含双重编码）。
     *
     * <p>反序列化时忽略未知字段：ui-plugin 的 controller 以泛型 Map 写入各配置段，
     * 可能含 POJO 没有的字段，关闭 FAIL_ON_UNKNOWN_PROPERTIES 避免整段反序列化失败。
     */
    private <T> T read(String key, Class<T> type) {
        Object value = store.get(key);
        if (value == null) {
            return null;
        }
        try {
            if (type.isInstance(value)) {
                return type.cast(value);
            }
            if (value instanceof String s) {
                try {
                    return tolerantRead(s, type);
                } catch (Exception inner) {
                    // 运行期 putJSON 会再包一层 JSON 字符串，剥一层再解析
                    String unwrapped = MAPPER.readValue(s, String.class);
                    return tolerantRead(unwrapped, type);
                }
            }
            return MAPPER.convertValue(value, type);
        } catch (Exception e) {
            log.warn("读取配置段失败: key={}, type={}, error={}", key, type.getSimpleName(), e.getMessage());
            return null;
        }
    }

    private <T> T tolerantRead(String json, Class<T> type) throws JsonProcessingException {
        return MAPPER.readerFor(type)
                .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .readValue(json);
    }
}
