package cn.seifly.jharness.plugin.app.core;

import cn.seifly.jharness.plugin.framework.HostEnv;
import cn.seifly.jharness.plugin.framework.redis.ConfigStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 宿主能力实现：SDK 中 {@link HostEnv} 契约的具体实现，
 * 插件通过 {@code @Autowired HostEnv} 即可注入并使用。
 */
@Component
public class DefaultHostEnv implements HostEnv {

    private final String appName;

    public DefaultHostEnv(@Value("${spring.application.name:seifly-plugin}") String appName) {
        this.appName = appName;
    }

    @Override
    public String appName() {
        return appName;
    }

    @Override
    public ConfigStore configStore() {
        return null;
    }
}
