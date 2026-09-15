package cn.seifly.jharness.plugin.framework.redis;

import cn.seifly.jharness.plugin.framework.HostEnv;
import org.pf4j.PluginManager;

import java.util.List;

/**
 * Redis 共享存储访问入口（插件侧静态门面）。
 *
 * <p>插件代码通过 {@link #store()} 获取 {@link ConfigStore} 实例，
 * 无需关心 Redis 连接细节 —— 由宿主在启动时注入实现。
 *
 * <p>使用方式：
 * <pre>{@code
 * ConfigStore store = RedisShared.store();
 * store.put(RedisKeys.MODELS_PROVIDERS, providersJson);
 * String json = store.get(RedisKeys.MODELS_PROVIDERS);
 * }</pre>
 */
public final class RedisShared {

    private static volatile ConfigStore instance;

    private RedisShared() {
    }

    /**
     * 由宿主在启动时调用，注入 {@link ConfigStore} 实现。
     *
     * @param store 宿主创建的 Redis 配置存储实例
     */
    public static void init(ConfigStore store) {
        instance = store;
    }

    /**
     * 获取共享配置存储实例。
     *
     * <p>插件代码调用前需确保宿主已通过 {@link #init} 注入实例，
     * 否则抛出 {@link IllegalStateException}。
     *
     * @return 共享配置存储
     * @throws IllegalStateException 未初始化时抛出
     */
    public static ConfigStore store() {
        ConfigStore s = instance;
        if (s == null) {
            throw new IllegalStateException(
                "RedisShared 未初始化：宿主未注入 ConfigStore 实例。" +
                "请确保宿主应用已启动并调用 RedisShared.init()。"
            );
        }
        return s;
    }

    /**
     * 从 PF4J PluginManager 关联的 HostEnv 获取 ConfigStore（可选方式）。
     *
     * @param manager PF4J 插件管理器
     * @return 配置存储实例
     */
    public static ConfigStore fromHost(PluginManager manager) {
        List<HostEnv> envs = manager.getExtensions(HostEnv.class);
        HostEnv env = (envs != null && !envs.isEmpty()) ? envs.get(0) : null;
        if (env == null) {
            throw new IllegalStateException("HostEnv 扩展点未注册");
        }
        return env.configStore();
    }
}
