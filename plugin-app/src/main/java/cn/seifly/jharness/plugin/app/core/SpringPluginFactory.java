package cn.seifly.jharness.plugin.app.core;

import org.pf4j.DefaultPluginFactory;
import org.pf4j.Plugin;
import org.pf4j.PluginWrapper;
import org.springframework.context.ApplicationContext;

/**
 * 自定义 PF4J 插件工厂：在默认工厂创建插件实例后，对插件本身执行 Spring 依赖注入。
 *
 * <p>默认的 {@link DefaultPluginFactory} 仅通过反射实例化 {@link Plugin} 子类，
 * 插件类中的 {@code @Autowired} 不会生效。这里在创建完成后调用
 * {@code autowireBean}，使插件生命周期类也能注入主应用容器中的 Bean
 * （如 {@link PluginControllerRegistry}），从而在 start()/stop() 中管理插件自身资源。
 */
public class SpringPluginFactory extends DefaultPluginFactory {

    private final ApplicationContext applicationContext;

    public SpringPluginFactory(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    public Plugin create(PluginWrapper pluginWrapper) {
        Plugin plugin = super.create(pluginWrapper);
        applicationContext.getAutowireCapableBeanFactory().autowireBean(plugin);
        return plugin;
    }
}
