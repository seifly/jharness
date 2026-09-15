package cn.seifly.jharness.plugins.workflow.context;

import cn.seifly.jharness.plugin.framework.memory.MemoryService;
import cn.seifly.jharness.plugins.evolution.PromptOptimizer;
import cn.seifly.jharness.plugins.skills.SkillsLoader;
import cn.seifly.jharness.plugin.framework.tools.ToolService;
import lombok.Getter;

/**
 * 传递给 ContextSection.build() 的上下文信息。
 * 包含构建 section 时可能需要的所有共享状态。
 *
 * <p>#2 重构后 tools / memory 字段类型提升到 SDK 契约
 * （{@link ToolService} / {@link MemoryService}），不再编译期依赖
 * tools-plugin / memory-plugin 的具体实现类。</p>
 */
@Getter
public class SectionContext {
    private final String currentMessage;
    private final String workspace;
    private final int contextWindow;
    private final ToolService tools;
    private final PromptOptimizer promptOptimizer;
    private final SkillsLoader skillsLoader;
    private final MemoryService memory;

    public SectionContext(String currentMessage, String workspace, int contextWindow,
                         ToolService tools, PromptOptimizer promptOptimizer,
                         SkillsLoader skillsLoader, MemoryService memory) {

        this.currentMessage = currentMessage;
        this.workspace = workspace;
        this.contextWindow = contextWindow;
        this.tools = tools;
        this.promptOptimizer = promptOptimizer;
        this.skillsLoader = skillsLoader;
        this.memory = memory;
    }
}
