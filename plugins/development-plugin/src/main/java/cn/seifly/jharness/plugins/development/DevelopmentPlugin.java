package cn.seifly.jharness.plugins.development;

import lombok.extern.slf4j.Slf4j;
import org.pf4j.Plugin;
import org.pf4j.PluginWrapper;

/**
 * development-plugin 生命周期类。
 *
 * <p>类名必须与 MANIFEST 中 {@code Plugin-Class} 一致。
 * start() / stop() 在插件「启动 / 停止」时回调，可用于初始化 / 释放插件资源。
 *
 * <p>负责开发辅助相关能力的扩展点实现与资源挂载。
 */
@Slf4j
public class DevelopmentPlugin extends Plugin {

    public DevelopmentPlugin(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public void start() {
        log.info("DevelopmentPlugin 启动完成");
    }

    @Override
    public void stop() {
        log.info("DevelopmentPlugin 已停止，资源已释放");
    }
}
