package cn.seifly.jharness.plugins.tools;

import cn.seifly.jharness.plugin.framework.service.ServiceRegistration;
import cn.seifly.jharness.plugin.framework.service.ServiceRegistry;
import cn.seifly.jharness.plugin.framework.tools.ToolService;
import cn.seifly.jharness.plugin.framework.tools.TokenUsageService;
import org.pf4j.Plugin;
import org.pf4j.PluginWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * tools-plugin 生命周期类。
 *
 * <p>类名必须与 MANIFEST 中 {@code Plugin-Class} 一致。
 * start() / stop() 在插件「启动 / 停止」时回调，用于初始化 / 释放插件资源。
 *
 * <p>#2 重构后职责：在 {@code start()} 内创建 {@link ToolRegistry}（实现
 * {@link ToolService}）与 {@link TokenUsageStore}（实现 {@link TokenUsageService}），
 * 通过 {@link ServiceRegistry} 注册为具名服务（{@code "tools"}），供 workflow-plugin
 * 等消费方通过 {@code services.get(ToolService.class)} / {@code services.get(TokenUsageService.class)}
 * 取得，不再编译期依赖 tools-plugin 的实现类。
 *
 * <p>workspace 路径由 workflow 在配置加载后通过 {@link TokenUsageService#init} 推入
 * （消费方 push 模型，与 #1 LlmService.reload 一致）；ToolRegistry 无需初始化数据，
 * 可直接对外提供空注册表，后续各插件实现自己的 Tool 后通过 ToolService.register 注册。
 */
@Slf4j
public class ToolsPlugin extends Plugin {

    private static final String SERVICE_NAME = "tools";

    @Autowired
    private ServiceRegistry services;

    private ServiceRegistration<ToolService> toolServiceReg;
    private ServiceRegistration<TokenUsageService> tokenUsageServiceReg;

    public ToolsPlugin(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public void start() {
        ToolRegistry toolRegistry = new ToolRegistry();
        TokenUsageStore tokenUsageStore = new TokenUsageStore();

        toolServiceReg = services.register(ToolService.class, toolRegistry, SERVICE_NAME);
        tokenUsageServiceReg = services.register(TokenUsageService.class, tokenUsageStore, SERVICE_NAME);

        log.info("ToolsPlugin 启动完成: 注册 ToolService / TokenUsageService, name={}", SERVICE_NAME);
    }

    @Override
    public void stop() {
        if (toolServiceReg != null) {
            toolServiceReg.unregister();
            toolServiceReg = null;
        }
        if (tokenUsageServiceReg != null) {
            tokenUsageServiceReg.unregister();
            tokenUsageServiceReg = null;
        }
        log.info("ToolsPlugin 已停止，资源已释放");
    }
}
