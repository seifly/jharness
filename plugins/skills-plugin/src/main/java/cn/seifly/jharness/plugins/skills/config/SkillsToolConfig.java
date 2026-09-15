package cn.seifly.jharness.plugins.skills.config;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * 技能工具配置
 *
 * 配置技能搜索的市场源和安全策略。
 * 从原 jclaw 全局 ToolsConfig 中拆分迁入 skills-plugin。
 */
@Getter
@Setter
public class SkillsToolConfig {

    /**
     * 可信技能市场源列表
     *
     * 每个条目包含 name、repo、description、enabled 字段。
     * 默认包含内置的官方和社区市场源。
     * 用户可以添加自定义的可信源。
     */
    private List<RegistryConfig> registries;

    /**
     * 是否允许 GitHub 全网搜索
     *
     * 默认为 false，只从可信市场源搜索。
     * 设为 true 后，当可信源中找不到匹配技能时，
     * 会降级到 GitHub 全网搜索（带安全警告）。
     */
    private boolean allowGlobalSearch;

    /**
     * GitHub Token（可选）
     *
     * 用于提高 GitHub API 速率限制。
     * 未认证：每分钟 10 次搜索请求
     * 已认证：每分钟 30 次搜索请求
     */
    private String githubToken;

    public SkillsToolConfig() {
        this.registries = new ArrayList<>();
        this.allowGlobalSearch = false;
        this.githubToken = "";
    }
}
