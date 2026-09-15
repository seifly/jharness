package cn.seifly.jharness.plugin.framework.service;

import java.util.List;
import java.util.Optional;

/**
 * 插件服务注册表。
 *
 * <p>SDK 共享的服务发现契约，由主应用（plugin-app）提供 Spring 单例实现，
 * 供所有插件通过 {@code @Autowired} 注入。
 *
 * <p>提供方用法（在 {@code Plugin.start()} 中）：
 * <pre>{@code
 * ServiceRegistration<LlmService> reg =
 *     serviceRegistry.register(LlmService.class, new DeepSeekLlmService());
 * // 保存 reg，在 stop() 中调用 reg.unregister()
 * }</pre>
 *
 * <p>消费方用法：
 * <pre>{@code
 * @Autowired ServiceRegistry services;
 *
 * LlmService llm = services.get(LlmService.class).orElseThrow();
 * Completion c = llm.complete(req);
 * }</pre>
 *
 * <p>所有方法线程安全；返回的服务实例由提供方插件 ClassLoader 加载，
 * 消费方仅能调用 {@code Service} 接口契约（SDK 共享 ClassLoader），
 * 不可直接引用实现类。
 */
public interface ServiceRegistry {

    /**
     * 注册一个服务实现。
     *
     * @param type    服务契约类型，必须是 SDK 内可见的 {@link Service} 子接口
     * @param service 实现实例
     * @param name    可选名称，用于一个契约有多个实现的场景（如 {@code "deepseek"} / {@code "anthropic"}）；
     *                为 null 时使用实现类的 simpleName
     * @return 注册句柄，用于后续注销
     */
    <S extends Service> ServiceRegistration<S> register(Class<S> type, S service, String name);

    /**
     * 注册一个服务实现，使用实现类 simpleName 作为名称。
     */
    default <S extends Service> ServiceRegistration<S> register(Class<S> type, S service) {
        return register(type, service, null);
    }

    /**
     * 取该契约的「首个」有效实现。无注册返回 {@link Optional#empty()}。
     *
     * <p>「首个」由注册顺序决定，无优先级语义。需要按名取或枚举全部请用
     * {@link #get(Class, String)} / {@link #getAll(Class)}。
     */
    <S extends Service> Optional<S> get(Class<S> type);

    /**
     * 按名称取该契约的具名实现。
     */
    <S extends Service> Optional<S> get(Class<S> type, String name);

    /**
     * 取该契约的全部有效实现。
     */
    <S extends Service> List<S> getAll(Class<S> type);

    /**
     * 列举该契约的全部有效实现名称（便于管理界面展示）。
     */
    <S extends Service> List<String> getNames(Class<S> type);
}
