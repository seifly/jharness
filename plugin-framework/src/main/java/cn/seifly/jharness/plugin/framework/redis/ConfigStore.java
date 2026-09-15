package cn.seifly.jharness.plugin.framework.redis;

import cn.seifly.jharness.plugin.framework.config.Config;
import com.fasterxml.jackson.core.JsonProcessingException;

/**
 * 配置存储契约（SDK SPI，由主应用实现 Redis 版本）。
 *
 * <p>所有插件共享同一份配置数据，解决 PF4J 独立类加载器下插件间无法直接共享对象的问题。
 *
 * <p>典型流程：
 * <ul>
 *   <li>各插件 {@code start()} 时从配置文件加载到 Redis（{@link #put}）</li>
 *   <li>ui-plugin 控制器从 Redis 读取配置（{@link #get} / {@link #getJSON}）</li>
 *   <li>控制器修改后写回 Redis（{@link #put}），并同步回写配置文件（由调用方负责）</li>
 * </ul>
 *
 * <p>命名空间约定：key 格式为 {@code plugin_id:section}，例如
 * {@code models:providers}、{@code sessions:default}、{@code workflow:agent_config}。
 */
public interface ConfigStore {
    Config getConfig();

    /**
     * 读取原始字符串配置值。
     *
     * @param key 配置键，如 {@code "models:providers"}
     * @return 配置值；不存在时返回 {@code null}
     */
    String get(String key) throws JsonProcessingException;

    /**
     * 读取 JSON 配置并反序列化为指定类型。
     *
     * @param key  配置键
     * @param type 目标类型
     * @return 反序列化后的对象；不存在时返回 {@code null}
     */
    <T> T getJSON(String key, Class<T> type);

    /**
     * 写入配置值（字符串）。
     *
     * @param key   配置键
     * @param value 配置值
     */
    void put(String key, String value);

    /**
     * 写入配置值（对象序列化为 JSON）。
     *
     * @param key   配置键
     * @param value 配置对象
     */
    void putJSON(String key, Object value);

    /**
     * 判断配置键是否存在。
     *
     * @param key 配置键
     * @return 存在返回 true
     */
    boolean exists(String key);

    /**
     * 删除配置键。
     *
     * @param key 配置键
     */
    void delete(String key);

    /**
     * 列出指定命名空间下的所有键。
     *
     * @param namespace 命名空间前缀，如 {@code "models:"}
     * @return 匹配的键集合
     */
    java.util.Set<String> keys(String namespace);
}
