package cn.seifly.jharness.plugin.framework.service;

/**
 * 服务注册句柄。
 *
 * <p>由 {@link ServiceRegistry#register} 返回，提供方需保存该句柄，
 * 在插件 {@code stop()} 时调用 {@link #unregister()} 移除注册，避免
 * 已卸载 ClassLoader 的实例被消费方继续持有。
 *
 * <p>若插件未显式调用 {@link #unregister()}，{@code ServiceRegistry} 实现
 * 应在插件停止 / 卸载时通过 {@code PluginStateListener} 兜底清理。
 */
public interface ServiceRegistration<S extends Service> {

    /**
     * 注销当前注册项。
     *
     * <p>幂等：重复调用不应抛出异常。
     */
    void unregister();

    /**
     * 注册时使用的服务类型契约。
     *
     * @return 服务类型（如 {@code LlmService.class}）
     */
    Class<S> serviceType();

    /**
     * 注册时使用的可选名称（一个服务类型可有多个具名实现）。
     *
     * @return 名称；未指定时返回实现类的 simpleName
     */
    String name();

    /**
     * 该注册项是否仍然有效（未注销且来源插件仍处于 STARTED 状态）。
     *
     * @return 有效返回 true
     */
    boolean isValid();
}
