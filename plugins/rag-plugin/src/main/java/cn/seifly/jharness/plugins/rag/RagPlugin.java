package cn.seifly.jharness.plugins.rag;

import cn.seifly.jharness.plugin.framework.web.PluginControllerRegistry;
import cn.seifly.jharness.plugins.rag.controller.KnowledgeBaseController;
import cn.seifly.jharness.plugins.rag.controller.RagController;
import cn.seifly.jharness.plugins.rag.controller.RagWebController;
import lombok.extern.slf4j.Slf4j;
import org.pf4j.Plugin;
import org.pf4j.PluginWrapper;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * RAG 插件生命周期类。
 *
 * <p>JHarness「一切皆插件」的落地：
 * <ul>
 *   <li>start()：创建插件私有 Spring 子应用上下文（父上下文为主应用），
 *       装配全部 RAG Bean（VectorStore / EmbeddingService / Service / Controller），
 *       并将 REST 控制器挂载到主应用 MVC；</li>
 *   <li>stop()：注销控制器、关闭子上下文，释放插件资源（热插拔）。</li>
 * </ul>
 *
 * <p>插件内部 Bean 完全由私有子上下文管理，主应用不直接感知具体实现，
 * 满足「业务层只依赖 VectorStore 等接口」的解耦要求。
 */
@Slf4j
public class RagPlugin extends Plugin {

    @Autowired(required = false)
    private PluginControllerRegistry controllerRegistry;

    @Autowired
    private ApplicationContext applicationContext;

    /** 插件私有子应用上下文（生命周期随插件） */
    private AnnotationConfigApplicationContext pluginContext;

    /** 已注册的控制器实例列表，用于 stop() 统一注销（参照 UiPlugin 的做法） */
    private final List<Object> registeredControllers = new ArrayList<>();

    public RagPlugin(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public void start() {
        log.info("RagPlugin 启动中...");

        AnnotationConfigApplicationContext child = new AnnotationConfigApplicationContext();
        child.setParent(applicationContext);

        // 把主机配置源合并到子上下文，使 @Value / Environment 能解析 rag.* / redis.* / embedding.*
        // 主机配置源先加入（较高优先级），可覆盖插件默认配置
        ConfigurableEnvironment childEnv = child.getEnvironment();
        ConfigurableEnvironment hostEnv = ((ConfigurableApplicationContext) applicationContext).getEnvironment();
        for (PropertySource<?> ps : hostEnv.getPropertySources()) {
            childEnv.getPropertySources().addLast(ps);
        }
        // 再加载插件自带的 application.yml 作为默认值（最后加入 = 最低优先级）
        Properties pluginProps = loadPluginDefaultProps();
        if (pluginProps != null && !pluginProps.isEmpty()) {
            childEnv.getPropertySources().addLast(
                    new PropertiesPropertySource("ragPluginDefaults", pluginProps));
            log.info("RagPlugin 已加载插件默认 application.yml，keys: {}", pluginProps.keySet());
        }

        child.register(RagPluginConfiguration.class);
        child.refresh();
        this.pluginContext = child;

        // 挂载到主应用 MVC。逐个注册并单独捕获异常：单个控制器注册失败不应阻断其余控制器
        // （否则 RagController 注册失败会导致 Web UI 也一并 404）。参照 UiPlugin 的做法。
        if (controllerRegistry == null) {
            log.warn("RagPlugin 未获取到 PluginControllerRegistry，REST 接口与 Web UI 不可用");
        } else {
            registerController(child.getBean(RagController.class), "REST 接口 /api/rag/*");
            registerController(child.getBean(KnowledgeBaseController.class), "REST 接口 /api/rag/knowledge-bases");
            registerController(child.getBean(RagWebController.class), "Web UI /rag/ 与 /reg/");
        }
        log.info("RagPlugin 启动完成，已注册 {} 个控制器", registeredControllers.size());
    }

    /**
     * 读取插件自带 {@code application.yml}（含 embedding.* / rag.* / redis.* 默认值）。
     *
     * <p><b>必须用插件自身 ClassLoader 读取</b>：PF4J 下插件资源位于插件独立
     * ClassLoader（插件 jar 内），默认 {@code new ClassPathResource(...)} 走线程上下文/
     * 主机 ClassLoader，只能读到主应用的 application.yml，导致插件的 embedding.* 等默认
     * 配置加载不到（参照 RagWebController 读取 reg/index.html 的做法）。
     *
     * @return 转换后的 Properties；找不到或解析失败返回 null（不影响插件启动）
     */
    private Properties loadPluginDefaultProps() {
        try {
            // YAML 无法用 ResourcePropertySource 直接读（它只支持 .properties），
            // 先用 YamlPropertiesFactoryBean 转成 Properties，再包成 PropertiesPropertySource
            YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
            yaml.setResources(new ClassPathResource("application.yml",
                    getWrapper().getPluginClassLoader()));
            return yaml.getObject();
        } catch (Exception e) {
            log.warn("加载插件默认 application.yml 失败（忽略，将仅使用主机配置）: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 注册单个控制器；失败只记录日志，不影响其它控制器。
     */
    private void registerController(Object controller, String desc) {
        try {
            controllerRegistry.register(controller);
            registeredControllers.add(controller);
            log.info("RagPlugin 已注册: {}", desc);
        } catch (Exception e) {
            log.error("RagPlugin 注册失败: {} - {}", desc, e.getMessage(), e);
        }
    }

    @Override
    public void stop() {
        if (controllerRegistry != null) {
            for (Object c : registeredControllers) {
                try {
                    controllerRegistry.unregister(c);
                } catch (Exception e) {
                    log.warn("RagPlugin 注销控制器失败: {}", c.getClass().getSimpleName(), e);
                }
            }
            if (!registeredControllers.isEmpty()) {
                log.info("RagPlugin 已注销 {} 个控制器", registeredControllers.size());
                registeredControllers.clear();
            }
        }
        if (pluginContext != null) {
            pluginContext.close();
            pluginContext = null;
        }
        log.info("RagPlugin 已停止，资源已释放");
    }
}
