package cn.seifly.jharness.plugins.sessions;

import lombok.extern.slf4j.Slf4j;
import org.pf4j.Plugin;
import org.pf4j.PluginWrapper;

/**
 * sessions-plugin 生命周期类。
 *
 * <p>类名必须与 MANIFEST 中 {@code Plugin-Class} 一致。
 * start() / stop() 在插件「启动 / 停止」时回调，可用于初始化 / 释放插件资源。
 *
 * <p>负责会话管理相关能力的扩展点实现与资源挂载。
 */
@Slf4j
public class SessionsPlugin extends Plugin {

    public SessionsPlugin(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public void start() {
        log.info("SessionsPlugin 启动完成");
    }

    @Override
    public void stop() {
        log.info("SessionsPlugin 已停止，资源已释放");
    }
}
