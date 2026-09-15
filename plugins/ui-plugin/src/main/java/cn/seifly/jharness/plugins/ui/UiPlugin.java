package cn.seifly.jharness.plugins.ui;

import cn.seifly.jharness.plugin.framework.web.PluginControllerRegistry;
import cn.seifly.jharness.plugins.ui.controller.AuthController;
import cn.seifly.jharness.plugins.ui.controller.ChannelsController;
import cn.seifly.jharness.plugins.ui.controller.ChatController;
import cn.seifly.jharness.plugins.ui.controller.ConfigController;
import cn.seifly.jharness.plugins.ui.controller.CronController;
import cn.seifly.jharness.plugins.ui.controller.FeedbackController;
import cn.seifly.jharness.plugins.ui.controller.FilesController;
import cn.seifly.jharness.plugins.ui.controller.HealthController;
import cn.seifly.jharness.plugins.ui.controller.MCPController;
import cn.seifly.jharness.plugins.ui.controller.ModelsController;
import cn.seifly.jharness.plugins.ui.controller.ProvidersController;
import cn.seifly.jharness.plugins.ui.controller.SessionsController;
import cn.seifly.jharness.plugins.ui.controller.SkillsController;
import cn.seifly.jharness.plugins.ui.controller.TokenStatsController;
import cn.seifly.jharness.plugins.ui.controller.UploadController;
import cn.seifly.jharness.plugins.ui.controller.WebResourceController;
import cn.seifly.jharness.plugins.ui.controller.WorkspaceController;
import lombok.extern.slf4j.Slf4j;
import org.pf4j.Plugin;
import org.pf4j.PluginWrapper;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;

/**
 * ui-plugin 生命周期类。
 *
 * <p>类名必须与 MANIFEST 中 {@code Plugin-Class} 一致。
 * start() / stop() 在插件「启动 / 停止」时回调，可用于初始化 / 释放插件资源。
 *
 * <p>负责 UI 界面相关能力的扩展点实现与资源挂载：
 * <ul>
 *   <li>{@link WebResourceController} 承载 Web Console 静态资源（/web/**、/css/**、/js/**、/sw.js）</li>
 *   <li>{@link ChatController} 等业务控制器承载 REST API（/api/**）</li>
 * </ul>
 * 由于插件 ClassLoader 加载的 @RestController 不会进入主应用组件扫描，
 * 必须在此显式通过 {@link PluginControllerRegistry} 注册到主应用 Spring MVC，
 * 插件停止时统一注销，实现接口热插拔。
 */
@Slf4j
public class UiPlugin extends Plugin {

    @Autowired(required = false)
    private PluginControllerRegistry controllerRegistry;

    /** 已注册的控制器实例列表（含静态资源控制器与业务 API 控制器），用于 stop() 统一注销 */
    private final List<Object> registeredControllers = new ArrayList<>();

    public UiPlugin(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public void start() {
        if (controllerRegistry == null) {
            log.warn("UiPlugin 未获取到 PluginControllerRegistry，Web Console 与 REST API 不可用");
            return;
        }

        // 1. Web Console 静态资源控制器
        register(() -> new WebResourceController());

        // 2. 业务 API 控制器（@Autowired 字段由 SpringMvcPluginControllerRegistry.register
        //    内部 autowireBean 注入，故直接 new 即可）。
        // 单个控制器构造失败（如依赖未就绪）不应阻断其余控制器注册。
        register(() -> new AuthController());
        register(() -> new ChannelsController());
        register(() -> new ChatController());
        register(() -> new ConfigController());
        register(() -> new CronController());
        register(() -> new FeedbackController());
        register(() -> new FilesController());
        register(() -> new HealthController());
        register(() -> new MCPController());
        register(() -> new ModelsController());
        register(() -> new ProvidersController());
        register(() -> new SessionsController());
        register(() -> new SkillsController());
        register(() -> new TokenStatsController());
        register(() -> new UploadController());
        register(() -> new WorkspaceController());

        log.info("UiPlugin 启动完成，已注册 {} 个控制器", registeredControllers.size());
    }

    @Override
    public void stop() {
        if (controllerRegistry == null) {
            return;
        }
        for (Object controller : registeredControllers) {
            try {
                controllerRegistry.unregister(controller);
            } catch (Exception e) {
                log.warn("注销控制器失败: {}", controller.getClass().getSimpleName(), e);
            }
        }
        int count = registeredControllers.size();
        registeredControllers.clear();
        log.info("UiPlugin 已停止，注销 {} 个控制器", count);
    }

    private void register(java.util.function.Supplier<Object> factory) {
        try {
            Object controller = factory.get();
            controllerRegistry.register(controller);
            registeredControllers.add(controller);
        } catch (Exception e) {
            log.error("注册控制器失败: {}", e.getMessage(), e);
        }
    }
}
