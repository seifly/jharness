package cn.seifly.jharness.plugin.framework.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.Setter;

/**
 * 进化能力配置（SDK 共享），控制反馈收集和 Prompt 优化的行为。
 *
 * <p>此类作为跨插件共享数据结构提升至 plugin-framework SDK，
 * 避免 PF4J 独立类加载器导致 workflow-plugin 在运行期抛出
 * {@code NoClassDefFoundError}。
 *
 * <p>所有进化功能默认关闭，需要显式启用。
 */
@Getter
@Setter
public class EvolutionConfig {

    public enum OptimizationStrategy {
        /** 自我反思：Agent 回顾最近会话的交互质量，自动改进 Prompt。 */
        SELF_REFINE
    }

    private boolean feedbackEnabled = false;
    private int feedbackRetentionDays = 30;
    private boolean implicitFeedbackEnabled = true;

    private double toolSuccessWeight = 0.3;
    private double retryPenaltyWeight = 0.2;
    private double sessionLengthWeight = 0.1;

    private boolean promptOptimizationEnabled = false;
    private OptimizationStrategy optimizationStrategy = OptimizationStrategy.SELF_REFINE;
    private int optimizationIntervalHours = 24;
    private double adoptionThreshold = 0.7;

    private boolean autoApplyOptimization = false;
    private double optimizationTemperature = 0.3;
    private int optimizationMaxTokens = 2048;
    private int maxHistoryVersions = 10;
    private int selfRefineSessionCount = 5;

    @JsonIgnore
    public boolean isAnyEvolutionEnabled() {
        return feedbackEnabled || promptOptimizationEnabled;
    }

    public boolean canOptimize() {
        return promptOptimizationEnabled;
    }

    public void setOptimizationStrategy(OptimizationStrategy s) {
        this.optimizationStrategy = s != null ? s : OptimizationStrategy.SELF_REFINE;
    }

    public void setAdoptionThreshold(double t) { this.adoptionThreshold = Math.max(0.0, Math.min(1.0, t)); }

    public void setSelfRefineSessionCount(int n) { this.selfRefineSessionCount = Math.max(1, n); }

    @Override
    public String toString() {
        return String.format("EvolutionConfig{feedback=%s, promptOpt=%s, strategy=%s, interval=%dh}",
                feedbackEnabled, promptOptimizationEnabled, optimizationStrategy, optimizationIntervalHours);
    }
}
