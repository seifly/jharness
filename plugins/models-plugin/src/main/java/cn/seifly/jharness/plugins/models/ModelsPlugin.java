package cn.seifly.jharness.plugins.models;

import cn.seifly.jharness.plugin.framework.llm.LlmService;
import cn.seifly.jharness.plugin.framework.service.ServiceRegistration;
import cn.seifly.jharness.plugin.framework.service.ServiceRegistry;
import org.pf4j.Plugin;
import org.pf4j.PluginWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * models-plugin 生命周期类。
 *
 * <p>类名必须与 MANIFEST 中 {@code Plugin-Class} 一致。
 * 在 {@code start()} 内创建 {@link HTTPProvider}（实现 SDK 契约 {@link LlmService}）并注册到
 * {@link ServiceRegistry}，供 workflow-plugin 等消费方通过
 * {@code services.get(LlmService.class, providerName)} 获取；{@code stop()} 内注销。
 *
 * <p>凭证注入采用「消费方 push」模型：本插件注册的实例初始 {@code apiBase} 为空（未就绪），
 * 由 workflow-plugin 的 {@code ProviderManager.reloadModel} 读取配置后调用
 * {@link LlmService#reload} 推入 {@code apiKey / apiBase}，实例随即就绪。
 */
@Slf4j
public class ModelsPlugin extends Plugin {

    /** 注册名：未来 DeepSeek / Anthropic 独立成插件时可用各自 provider 名注册 */
    private static final String SERVICE_NAME = "models";

    @Autowired
    private ServiceRegistry services;

    private volatile ServiceRegistration<LlmService> registration;

    public ModelsPlugin(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public void start() {
        // 注册一个可热重载凭证的 HTTPProvider 作为 LlmService 实现；
        // 初始 apiBase 为空 → isReady()=false，等待 workflow-plugin push 凭证后即就绪
        HTTPProvider provider = new HTTPProvider(null, null, SERVICE_NAME);
        this.registration = services.register(LlmService.class, provider, SERVICE_NAME);
        log.info("ModelsPlugin 启动完成: llm_service_registered=true, ready={}", provider.isReady());
    }

    @Override
    public void stop() {
        ServiceRegistration<LlmService> reg = this.registration;
        if (reg != null) {
            reg.unregister();
            this.registration = null;
        }
        log.info("ModelsPlugin 已停止，资源已释放");
    }
}
