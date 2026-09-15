package cn.seifly.jharness.plugins.skills;

import lombok.Getter;
import lombok.Setter;

/**
 * Skill information
 */
@Getter
@Setter
public class SkillInfo {

    private String name;
    private String path;
    private String source;
    private String description;

    public SkillInfo() {}
}
