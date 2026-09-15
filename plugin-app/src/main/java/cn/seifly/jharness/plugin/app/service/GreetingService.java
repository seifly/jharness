package cn.seifly.jharness.plugin.app.service;

import cn.seifly.jharness.plugin.app.core.SpringPluginManager;
import cn.seifly.jharness.plugin.framework.GreetingExtension;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 业务服务：演示如何调用「动态发现」的扩展点。
 *
 * <p>每次调用都会实时查询插件管理器，
 * 因此插件上传 / 卸载 / 启用 / 停用后，此处结果会立刻随之变化 ——
 * 这就是插件化「无代码侵入式扩展」的体现。
 */
@Service
public class GreetingService {

    private final SpringPluginManager pluginManager;

    public GreetingService(SpringPluginManager pluginManager) {
        this.pluginManager = pluginManager;
    }

    /**
     * 调用当前所有已加载且已启动的问候扩展。
     */
    public List<String> greet(String name) {
        return pluginManager.getExtensions(GreetingExtension.class)
                .stream()
                .map(ext -> "[" + ext.language() + "] " + ext.greeting(name))
                .toList();
    }

    /**
     * 返回当前注册的问候扩展实现类。
     */
    public List<String> extensionClasses() {
        return pluginManager.getExtensionClasses(GreetingExtension.class)
                .stream()
                .map(Class::getName)
                .toList();
    }
}
