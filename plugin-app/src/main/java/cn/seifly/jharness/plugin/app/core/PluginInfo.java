package cn.seifly.jharness.plugin.app.core;

import org.pf4j.PluginState;
import org.pf4j.PluginWrapper;

/**
 * 插件信息 DTO（用于 REST 接口返回）。
 */
public record PluginInfo(
        String id,
        String description,
        String version,
        String provider,
        String className,
        String path,
        PluginState state) {

    public static PluginInfo from(PluginWrapper wrapper) {
        return new PluginInfo(
                wrapper.getDescriptor().getPluginId(),
                wrapper.getDescriptor().getPluginDescription(),
                wrapper.getDescriptor().getVersion().toString(),
                wrapper.getDescriptor().getProvider(),
                wrapper.getDescriptor().getPluginClass(),
                wrapper.getPluginPath() == null ? "" : wrapper.getPluginPath().toString(),
                wrapper.getPluginState());
    }
}
