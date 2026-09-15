package cn.seifly.jharness.plugin.app.config;

import cn.seifly.jharness.plugin.app.core.ServiceRegistryImpl;
import cn.seifly.jharness.plugin.app.core.SpringPluginManager;
import cn.seifly.jharness.plugin.framework.redis.RedisShared;
import cn.seifly.jharness.plugin.framework.web.PluginControllerRegistry;
import org.pf4j.PluginState;
import org.pf4j.PluginWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * PF4J 装配配置。
 *
 * <p>应用启动时自动加载并启动 plugins 目录下已有的插件；
 * 应用销毁时通过 {@code destroyMethod = "stopPlugins"} 优雅地停止并卸载全部插件。
 */
@Slf4j
@Configuration
public class Pf4jConfiguration {

    @Value("${spring.pf4j.path:runtime-plugins}")
    private String pluginPath;

    @Bean(destroyMethod = "stopPlugins")
    public SpringPluginManager pluginManager(ApplicationContext applicationContext,
                                             PluginControllerRegistry pluginControllerRegistry,
                                             ServiceRegistryImpl serviceRegistry) throws IOException {
        Path root = Path.of(pluginPath).toAbsolutePath();
        Files.createDirectories(root);

        // pluginControllerRegistry 参数仅用于建立依赖关系：
        // 确保它先于插件加载创建，插件启动 autowire 时一定能注入到
        // （否则插件 @Autowired PluginControllerRegistry 可能注入失败）。
        SpringPluginManager manager = new SpringPluginManager(root, applicationContext);

        // 注入共享配置存储：开发期使用内存版，避免插件构造期 RedisShared.store() 抛异常
        // 生产部署应替换为 Redis 实现并在此注入（Redis 自身持久化，无需 shutdown 回写文件）。
        InMemoryConfigStore inMemoryStore = new InMemoryConfigStore();
        RedisShared.init(inMemoryStore);

        // 关闭时把内存中的配置回写到配置文件（~/.jclaw/config.json），
        // 使运行期修改过的配置（如微信通道 resumeContext）能在下次启动恢复。
        Runtime.getRuntime().addShutdownHook(new Thread(inMemoryStore::saveToFile, "config-persister"));

        // 1. 加载 plugins 目录下所有插件（含 disabled 状态恢复）
        manager.loadPlugins();
        List<PluginWrapper> loaded = manager.getPlugins();
        log.info("PF4J 已加载 {} 个插件，开始启动前状态分布: {}",
                loaded.size(), summarizeStateDistribution(loaded));

        // 1.5. 绑定 ServiceRegistry 与 PluginManager：注册 PluginStateListener，
        //      使插件停止时其注册的服务能被兜底清理。必须在 startPlugins() 之前，
        //      否则首个插件的 start() 内注册的服务在 bind 前已存在却无清理兜底。
        serviceRegistry.bind(manager);

        // 2. 启动所有可启动的插件（逐插件捕获异常，避免单个插件失败导致整体不启动）
        // 注意：PF4J 3.15 的 startPlugins() 返回 void；最终状态通过逐个 PluginWrapper 读取。
        manager.startPlugins();
        long startedCount = 0;
        long failedCount = 0;
        long stuckCount = 0;
        for (PluginWrapper pw : loaded) {
            PluginState s = pw.getPluginState();
            if (s == PluginState.STARTED) {
                startedCount++;
            } else if (s == PluginState.FAILED) {
                failedCount++;
                log.warn("插件启动失败: {} -> {}", pw.getDescriptor().getPluginId(), s);
            } else {
                stuckCount++;
                log.warn("插件未进入 STARTED 状态: {} -> {}", pw.getDescriptor().getPluginId(), s);
            }
        }
        log.info("PF4J 启动阶段完成：成功 STARTED={}, FAILED={}, 未达 STARTED={}",
                startedCount, failedCount, stuckCount);

        log.info("PF4J PluginManager 初始化完成，插件目录: {}", root);
        return manager;
    }

    private String summarizeStateDistribution(List<PluginWrapper> plugins) {
        if (plugins == null || plugins.isEmpty()) {
            return "(无插件)";
        }
        int created = 0, started = 0, stopped = 0, failed = 0, other = 0;
        for (PluginWrapper pw : plugins) {
            PluginState s = pw.getPluginState();
            if (s == null) { other++; continue; }
            switch (s) {
                case CREATED -> created++;
                case STARTED -> started++;
                case STOPPED -> stopped++;
                case FAILED -> failed++;
                default -> other++;
            }
        }
        return String.format("CREATED=%d, STARTED=%d, STOPPED=%d, FAILED=%d, OTHER=%d",
                created, started, stopped, failed, other);
    }
}
