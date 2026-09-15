package cn.seifly.jharness.plugin.app.core;

import org.pf4j.DefaultExtensionFactory;
import org.springframework.context.ApplicationContext;

/**
 * PF4J 与 Spring 集成的核心：自定义扩展工厂。
 *
 * <p>默认的 {@link DefaultExtensionFactory} 只通过无参构造创建扩展实例；
 * 这里在实例化完成后调用 Spring 的 {@code autowireBean}，
 * 使插件扩展类中的 {@code @Autowired} / {@code @Value} 等注解真正生效，
 * 即插件可以直接注入主应用容器中的 Bean（实现「插件反向使用宿主能力」）。
 */
public class SpringExtensionFactory extends DefaultExtensionFactory {

    private final ApplicationContext applicationContext;

    public SpringExtensionFactory(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    public <T> T create(Class<T> extensionClass) {
        T instance = super.create(extensionClass);
        // 将扩展实例纳入 Spring 容器管理（注入依赖，不注册为单例 Bean，随插件生命周期释放）
        applicationContext.getAutowireCapableBeanFactory().autowireBean(instance);
        return instance;
    }
}
