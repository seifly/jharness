package cn.seifly.jharness.plugins.hello;

import cn.seifly.jharness.plugin.framework.web.PluginControllerRegistry;
import cn.seifly.jharness.plugins.hello.controller.HelloController;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.pf4j.Plugin;
import org.pf4j.PluginWrapper;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * hello-plugin 生命周期类。
 *
 * <p>类名必须与 MANIFEST 中 {@code Plugin-Class} 一致。
 * start() / stop() 在插件「启动 / 停止」时回调，可用于初始化 / 释放插件资源。
 *
 * <p>插件实例由主应用的 {@code SpringPluginFactory} 创建并执行 Spring 依赖注入，
 * 因此这里可以直接 {@code @Autowired} 主应用提供的 {@link PluginControllerRegistry}，
 * 在启动时挂载插件自身的 REST 接口、停止时卸载。
 */
@Slf4j
public class HelloPlugin extends Plugin {

    @Autowired(required = false)
    private PluginControllerRegistry controllerRegistry;

    /** 插件自身的 REST 控制器实例（生命周期由插件管理） */
    private HelloController helloController;

    public HelloPlugin(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public void start() {
        // StringUtils 来自 commons-lang3，随插件包分发，演示「插件自带第三方依赖」
        log.info("HelloPlugin 启动完成，欢迎语: {}", StringUtils.abbreviate("你好，PF4J 世界！", 20));

        // 将插件自带的 REST 控制器挂载到主应用 Spring MVC
        if (controllerRegistry != null) {
            helloController = new HelloController();
            controllerRegistry.register(helloController);
            log.info("HelloPlugin 已注册 REST 接口: GET /api/hello/say, GET /api/hello/ping");
        } else {
            log.warn("HelloPlugin 未获取到 PluginControllerRegistry（主应用未提供），REST 接口不可用");
        }
    }

    @Override
    public void stop() {
        // 卸载插件前先注销其 REST 接口，避免路由悬空
        if (controllerRegistry != null && helloController != null) {
            controllerRegistry.unregister(helloController);
            helloController = null;
        }
        log.info("HelloPlugin 已停止，资源已释放");
    }
}
