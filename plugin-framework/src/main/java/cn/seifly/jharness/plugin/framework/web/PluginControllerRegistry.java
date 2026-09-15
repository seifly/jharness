package cn.seifly.jharness.plugin.framework.web;

/**
 * 插件 REST 控制器注册器（SDK 契约，由主应用实现）。
 *
 * <p>插件中的 {@code @RestController} 由插件自己的 ClassLoader 加载，
 * 主应用 Spring 扫描不到，因此必须由插件显式注册：
 * 插件在 {@code start()} 中调用 {@link #register(Object)} 把控制器挂载到主应用 Spring MVC，
 * 在 {@code stop()} 中调用 {@link #unregister(Object)} 移除，实现接口热插拔。
 */
public interface PluginControllerRegistry {

    /**
     * 注册插件控制器：扫描类/方法上的 {@code @RequestMapping}（含 {@code @GetMapping} 等组合注解），
     * 动态挂载到主应用的 {@code RequestMappingHandlerMapping}，并对其执行 Spring 依赖注入。
     *
     * @param controller 插件控制器实例（类上须有 {@code @RestController} / {@code @RequestMapping}）
     */
    void register(Object controller);

    /**
     * 注销插件控制器：移除该实例此前注册的全部 URL 映射。
     *
     * @param controller 之前传入 {@link #register(Object)} 的同一实例
     */
    void unregister(Object controller);
}
