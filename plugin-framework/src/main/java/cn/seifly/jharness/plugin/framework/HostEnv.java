package cn.seifly.jharness.plugin.framework;

import cn.seifly.jharness.plugin.framework.redis.ConfigStore;

/**
 * 宿主能力契约（Host SPI）。
 *
 * <p>主应用通过 SDK 暴露自身能力，插件只依赖 SDK 即可安全地使用宿主功能，
 * 而无需反向依赖宿主应用的内部实现类 —— 这是插件化架构的关键约定之一。
 */
public interface HostEnv {

    /**
     * 宿主应用名称。
     */
    String appName();

    /**
     * 获取共享配置存储（Redis 实现）。
     *
     * <p>所有插件共用同一实例，用于跨插件配置数据共享：
     * <ul>
     *   <li>各插件 {@code start()} 时从配置文件加载到 Redis</li>
     *   <li>ui-plugin 控制器从 Redis 读取/修改配置</li>
     *   <li>修改后同步回写配置文件（由调用方负责）</li>
     * </ul>
     *
     * @return 共享配置存储实例
     */
    ConfigStore configStore();
}
