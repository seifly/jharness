package cn.seifly.jharness.plugin.app.config;

import cn.seifly.jharness.plugin.framework.config.AgentConfig;
import cn.seifly.jharness.plugin.framework.config.ChannelsConfig;
import cn.seifly.jharness.plugin.framework.config.Config;
import cn.seifly.jharness.plugin.framework.redis.ConfigStore;
import cn.seifly.jharness.plugin.framework.redis.RedisKeys;
import cn.seifly.jharness.plugin.framework.redis.RedisShared;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisException;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import lombok.extern.slf4j.Slf4j;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 基于 Redis（Lettuce）的 {@link ConfigStore} 实现。
 *
 * <p>由宿主应用在启动时创建并注入到 {@link RedisShared}，
 * 所有插件通过 {@link RedisShared#store()} 获取同一实例。
 *
 * <p>使用 Lettuce 单连接 + 异步命令（async 线程安全，可被多线程并发调用，
 * 通过阻塞等待返回值），支持配置：
 * <ul>
 *   <li>{@code jharness.redis.host}（默认 localhost）</li>
 *   <li>{@code jharness.redis.port}（默认 6379）</li>
 *   <li>{@code jharness.redis.password}（可选）</li>
 *   <li>{@code jharness.redis.database}（默认 0）</li>
 * </ul>
 *
 * <p>本类同时是 plugin-app 内 Redis 连接参数的<b>唯一来源</b>：host / port / password /
 * database 会保存为字段并通过 getter 暴露，供应用内其它需要连接同一 Redis 的组件复用
 * （例如 rag 插件的 Lettuce Vector Set 连接按此参数构建），避免各处各自读配置。
 */
@Slf4j
public class RedisConfigStore implements ConfigStore, AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 单次命令等待超时（连接断开 / 命令超时时的兜底，避免无限阻塞） */
    private static final long COMMAND_TIMEOUT_SECONDS = 5;

    private final String host;
    private final int port;
    private final String password;
    private final int database;

    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisAsyncCommands<String, String> async;

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public String getPassword() {
        return password;
    }

    public int getDatabase() {
        return database;
    }

    /**
     * 使用默认配置创建（localhost:6379）。
     */
    public RedisConfigStore() {
        this("localhost", 6379, null, 0);
    }

    /**
     * 使用系统属性配置创建。
     *
     * <p>读取属性：
     * <ul>
     *   <li>{@code jharness.redis.host}（默认 localhost）</li>
     *   <li>{@code jharness.redis.port}（默认 6379）</li>
     *   <li>{@code jharness.redis.password}（默认无）</li>
     *   <li>{@code jharness.redis.database}（默认 0）</li>
     * </ul>
     */
    public RedisConfigStore fromSystemProps() {
        String host = System.getProperty("jharness.redis.host", "localhost");
        int port = Integer.getInteger("jharness.redis.port", 6379);
        String password = System.getProperty("jharness.redis.password");
        int database = Integer.getInteger("jharness.redis.database", 0);
        return new RedisConfigStore(host, port, password, database);
    }

    /**
     * 指定连接参数创建。
     *
     * @param host     Redis 主机
     * @param port     Redis 端口
     * @param password 密码（可为 null）
     * @param database 数据库索引
     */
    public RedisConfigStore(String host, int port, String password, int database) {
        this.host = host;
        this.port = port;
        this.password = password;
        this.database = database;

        RedisURI.Builder uriBuilder = RedisURI.Builder.redis(host, port)
                .withDatabase(database);
        if (password != null && !password.isEmpty()) {
            uriBuilder.withPassword(password.toCharArray());
        }
        this.client = RedisClient.create(uriBuilder.build());
        this.connection = client.connect();
        this.async = connection.async();
        log.info("RedisConfigStore 初始化完成: host={}, port={}, database={}", host, port, database);
    }

    /**
     * 执行一条异步命令并阻塞等待结果；异常统一转成 {@link RedisException} 抛给调用方。
     */
    private <T> T await(RedisFuture<T> future) {
        try {
            return future.get(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RedisException("Redis 命令等待被中断", e);
        } catch (ExecutionException e) {
            throw new RedisException("Redis 命令执行失败", e);
        } catch (TimeoutException e) {
            throw new RedisException("Redis 命令执行超时（" + COMMAND_TIMEOUT_SECONDS + "s）", e);
        }
    }

    @Override
    public Config getConfig() {
        Config config = new Config();
        config.setAgent(getJSON(get(RedisKeys.WORKFLOW_AGENT_CONFIG), AgentConfig.class));
        config.setChannels(getJSON(get(RedisKeys.NOTIFICATIONS_CHANNELS), ChannelsConfig.class));
        return config;
    }

    @Override
    public String get(String key) {
        try {
            return await(async.get(key));
        } catch (Exception e) {
            log.error("Redis get 失败: key={}", key, e);
            return null;
        }
    }

    @Override
    public <T> T getJSON(String key, Class<T> type) {
        String json = get(key);
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            log.error("Redis JSON 反序列化失败: key={}, type={}", key, type.getSimpleName(), e);
            return null;
        }
    }

    @Override
    public void put(String key, String value) {
        try {
            await(async.set(key, value));
        } catch (Exception e) {
            log.error("Redis put 失败: key={}", key, e);
        }
    }

    @Override
    public void putJSON(String key, Object value) {
        try {
            String json = MAPPER.writeValueAsString(value);
            put(key, json);
        } catch (Exception e) {
            log.error("Redis JSON 序列化失败: key={}", key, e);
        }
    }

    @Override
    public boolean exists(String key) {
        try {
            Long count = await(async.exists(key));
            return count != null && count > 0;
        } catch (Exception e) {
            log.error("Redis exists 失败: key={}", key, e);
            return false;
        }
    }

    @Override
    public void delete(String key) {
        try {
            await(async.del(key));
        } catch (Exception e) {
            log.error("Redis delete 失败: key={}", key, e);
        }
    }

    @Override
    public Set<String> keys(String namespace) {
        try {
            List<String> matched = await(async.keys(namespace + "*"));
            return new HashSet<>(matched);
        } catch (Exception e) {
            log.error("Redis keys 失败: namespace={}", namespace, e);
            return new HashSet<>();
        }
    }

    @Override
    public void close() {
        if (connection != null) {
            connection.close();
        }
        if (client != null) {
            client.shutdown();
        }
        log.info("RedisConfigStore 已关闭");
    }
}
