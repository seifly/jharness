package cn.seifly.jharness.plugins.skills.config;

import lombok.Data;
import lombok.Getter;
import lombok.Setter;

/**
 * 单个技能市场源配置
 * 从原 jclaw 全局 ToolsConfig.RegistryConfig 中拆分迁入 skills-plugin。
 */
@Getter
@Setter
public class RegistryConfig {
    private String name;
    private String repo;
    private String description;
    private boolean enabled;

    public RegistryConfig() {
        this.enabled = true;
    }

    public RegistryConfig(String name, String repo, String description) {
        this.name = name;
        this.repo = repo;
        this.description = description;
        this.enabled = true;
    }
}
