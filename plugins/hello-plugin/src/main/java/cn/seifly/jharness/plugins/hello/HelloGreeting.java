package cn.seifly.jharness.plugins.hello;

import cn.seifly.jharness.plugin.framework.GreetingExtension;
import cn.seifly.jharness.plugin.framework.HostEnv;
import org.apache.commons.lang3.StringUtils;
import org.pf4j.Extension;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 中文问候扩展。
 *
 * <p>{@code @Extension} 注解使该实现被 PF4J 自动发现（编译期生成 extensions.idx）。
 * 通过 {@code @Autowired} 注入宿主能力，验证 Spring 集成已生效。
 */
@Extension(ordinal = 1)
public class HelloGreeting implements GreetingExtension {

    @Autowired
    private HostEnv hostEnv;

    @Override
    public String greeting(String name) {
        String msg = "你好, " + name + "！来自插件 [hello-plugin]，宿主应用: " + hostEnv.appName();
        return StringUtils.capitalize(msg);
    }

    @Override
    public String language() {
        return "zh-CN";
    }
}
