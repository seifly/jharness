package cn.seifly.jharness.plugin.framework;

import org.pf4j.ExtensionPoint;

/**
 * 问候扩展点：主应用暴露的「可插拔」能力。
 *
 * <p>插件只需实现本接口并标注 {@code @org.pf4j.Extension}，
 * 主应用即可通过 {@code PluginManager.getExtensions(GreetingExtension.class)} 动态发现并调用。
 */
public interface GreetingExtension extends ExtensionPoint {

    /**
     * 返回一句问候语。
     *
     * @param name 被问候的人
     */
    String greeting(String name);

    /**
     * 语言标识，如 zh-CN / en-US，用于在管理界面区分插件。
     */
    String language();
}
