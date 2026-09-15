package cn.seifly.jharness.plugins.workflow;

import cn.seifly.jharness.plugin.framework.security.SecurityService;
import cn.seifly.jharness.plugin.framework.service.AgentRuntimeService;
import cn.seifly.jharness.plugin.framework.service.ServiceRegistration;
import cn.seifly.jharness.plugin.framework.service.ServiceRegistry;
import org.pf4j.Plugin;
import org.pf4j.PluginWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * workflow-plugin 生命周期类。
 *
 * <p>类名必须与 MANIFEST 中 {@code Plugin-Class} 一致。
 * 在 {@code start()} 内构造 {@link WorkflowRuntime}（含 Config / ContextBuilder /
 * ProviderManager / MessageRouter 等跨插件依赖）并把 {@link AgentRuntimeService}
 * 实现注册到 {@link ServiceRegistry}，供 ui-plugin 等消费方通过
 * {@code services.get(AgentRuntimeService.class)} 获取；{@code stop()} 内注销。
 *
 * <p>{@link ServiceRegistry} 由主应用提供（Spring 单例），通过 {@code @Autowired}
 * 注入；SpringPluginFactory 在创建本插件实例时已完成 autowire，{@code start()}
 * 调用时字段已就位。
 */
@Slf4j
public class WorkflowPlugin extends Plugin {

    @Autowired
    private ServiceRegistry services;

    private volatile WorkflowRuntime runtime;
    private volatile ServiceRegistration<AgentRuntimeService> registration;
    // #4 SecurityService：包装 WorkflowRuntime 内的 SecurityGuard，供 ui-plugin 实时下发安全策略
    private volatile ServiceRegistration<SecurityService> securityRegistration;

    public WorkflowPlugin(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public void start() {
        // 1. 组装运行期对象：Config / ContextBuilder / ProviderManager / MessageRouter /
        //    SecurityGuard 等，传入 ServiceRegistry，使 ProviderManager 能取到由 models-plugin 注册的 LlmService
        WorkflowRuntime rt = new WorkflowRuntime(services);
        this.runtime = rt;

        // 2. 实现 AgentRuntimeService 并注册到 ServiceRegistry，供 ui-plugin 等消费方获取
        AgentRuntimeService impl = new AgentRuntimeServiceImpl(rt);
        this.registration = services.register(AgentRuntimeService.class, impl, "workflow");

        // 3. 注册 SecurityService：供 ui-plugin 的 ConfigController 在用户调整
        //    restrictToWorkspace / commandBlacklist 后实时下发到 workflow 内的 SecurityGuard
        this.securityRegistration = services.register(SecurityService.class, rt.securityService, "workflow");

        // 4. 启动入站消息消费者：从 MessageBus 拉取通道发布的入站消息并交给 MessageRouter 处理。
        //    这是外部通道（微信/Telegram 等）消息进入 LLM 处理链的入口。
        rt.startInboundConsumer();

        log.info("WorkflowPlugin 启动完成: agent_runtime_registered=true, security_registered=true, inbound_consumer_started=true, provider_configured={}",
                rt.providerManager.isConfigured());
    }

    @Override
    public void stop() {
        // 1. 先停止入站消费者，避免 bus 注销后仍在消费
        WorkflowRuntime rt = this.runtime;
        if (rt != null) {
            rt.stopInboundConsumer();
        }

        // 2. 注销 ServiceRegistration；幂等。即使忘记调用，
        // ServiceRegistryImpl 的 PluginStateListener 也会在 STOPPED 时兜底清理
        ServiceRegistration<AgentRuntimeService> reg = this.registration;
        if (reg != null) {
            reg.unregister();
            this.registration = null;
        }
        ServiceRegistration<SecurityService> secReg = this.securityRegistration;
        if (secReg != null) {
            secReg.unregister();
            this.securityRegistration = null;
        }
        this.runtime = null;
        log.info("WorkflowPlugin 已停止，资源已释放");
    }
}
