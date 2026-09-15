package cn.seifly.jharness.plugins.memory;

import cn.seifly.jharness.plugin.framework.memory.MemoryService;
import cn.seifly.jharness.plugin.framework.service.ServiceRegistration;
import cn.seifly.jharness.plugin.framework.service.ServiceRegistry;
import org.pf4j.Plugin;
import org.pf4j.PluginWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * memory-plugin 生命周期类。
 *
 * <p>类名必须与 MANIFEST 中 {@code Plugin-Class} 一致。
 * start() / stop() 在插件「启动 / 停止」时回调，用于初始化 / 释放插件资源。
 *
 * <p>#2 重构后职责：在 {@code start()} 内创建 {@link MemoryStore}（实现
 * {@link MemoryService}），通过 {@link ServiceRegistry} 注册为具名服务
 * （{@code "memory"}），供 workflow-plugin 等消费方通过
 * {@code services.get(MemoryService.class)} 取得，不再编译期依赖 memory-plugin 的实现类。
 *
 * <p>workspace 路径由 workflow 在配置加载后通过 {@link MemoryService#init} 推入
 * （消费方 push 模型，与 #1 LlmService.reload 一致）。MemoryEvolver 的进化触发
 * 由 memory-plugin 自身或 heartbeat-plugin 处理，不通过本契约暴露。
 */
@Slf4j
public class MemoryPlugin extends Plugin {

    private static final String SERVICE_NAME = "memory";

    @Autowired
    private ServiceRegistry services;

    private ServiceRegistration<MemoryService> registration;

    public MemoryPlugin(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public void start() {
        MemoryStore memoryStore = new MemoryStore();
        registration = services.register(MemoryService.class, memoryStore, SERVICE_NAME);
        log.info("MemoryPlugin 启动完成: 注册 MemoryService, name={}", SERVICE_NAME);
    }

    @Override
    public void stop() {
        if (registration != null) {
            registration.unregister();
            registration = null;
        }
        log.info("MemoryPlugin 已停止，资源已释放");
    }
}
