package cn.seifly.jharness.plugins.evolution;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 优化结果封装，记录一次优化操作的完整信息。
 *
 * 包含：
 * - 优化前后的 Prompt
 * - 评估指标变化
 * - 使用的策略和反馈
 * - 是否建议采用
 */
@Data
@NoArgsConstructor
public class OptimizationResult {

    /**
     * 优化状态
     */
    public enum Status {
        /**
         * 优化成功，生成了改进版本
         */
        SUCCESS,

        /**
         * 无需优化，当前版本已足够好
         */
        NO_IMPROVEMENT_NEEDED,

        /**
         * 优化失败，未能生成有效改进
         */
        FAILED,

        /**
         * 等待评估，优化版本已生成但尚未验证
         */
        PENDING_EVALUATION
    }

    // ==================== 核心字段 ====================

    /**
     * 结果 ID
     */
    private String id;

    /**
     * 优化状态
     */
    private Status status;

    /**
     * 原始 Prompt
     */
    private String originalPrompt;

    /**
     * 优化后的 Prompt
     */
    private String optimizedPrompt;

    /**
     * 文本梯度（优化建议）
     */
    private String textualGradient;

    /**
     * 使用的优化策略名称
     */
    private String strategy;

    /**
     * 输入的反馈数量
     */
    private int feedbackCount;

    /**
     * 优化前的预估评分
     */
    private double originalScore;

    /**
     * 优化后的预估评分
     */
    private double optimizedScore;

    /**
     * 评分提升幅度
     */
    private double improvement;

    /**
     * 是否建议采用此优化
     */
    private boolean recommendAdoption;

    /**
     * 不建议采用的原因（如果有）
     */
    private String rejectionReason;

    /**
     * 优化时间
     */
    private Instant timestamp;

    /**
     * 优化过程中的中间变体（如果使用 EVO_PROMPT）
     */
    private List<PromptVariant> variants;

    // ==================== 构造 ====================

    public OptimizationResult(String id, Status status, String originalPrompt, String optimizedPrompt,
                              String textualGradient, String strategy, int feedbackCount,
                              double originalScore, double optimizedScore, double improvement,
                              boolean recommendAdoption, String rejectionReason,
                              Instant timestamp, List<PromptVariant> variants) {
        this.id = id;
        this.status = status;
        this.originalPrompt = originalPrompt;
        this.optimizedPrompt = optimizedPrompt;
        this.textualGradient = textualGradient;
        this.strategy = strategy;
        this.feedbackCount = feedbackCount;
        this.originalScore = originalScore;
        this.optimizedScore = optimizedScore;
        this.improvement = improvement;
        this.recommendAdoption = recommendAdoption;
        this.rejectionReason = rejectionReason;
        this.timestamp = timestamp;
        this.variants = variants;
    }

    /**
     * 创建成功的优化结果。
     *
     * @param original  原始 Prompt
     * @param optimized 优化后的 Prompt
     * @param gradient  文本梯度
     * @param strategy  使用的策略
     * @return 优化结果
     */
    public static OptimizationResult success(String original, String optimized,
                                              String gradient, String strategy) {
        OptimizationResult result = new OptimizationResult();
        result.setStatus(Status.SUCCESS);
        result.setOriginalPrompt(original);
        result.setOptimizedPrompt(optimized);
        result.setTextualGradient(gradient);
        result.setStrategy(strategy);
        return result;
    }

    /**
     * 创建无需优化的结果。
     *
     * @param original 原始 Prompt
     * @param reason   原因
     * @return 优化结果
     */
    public static OptimizationResult noImprovementNeeded(String original, String reason) {
        OptimizationResult result = new OptimizationResult();
        result.setStatus(Status.NO_IMPROVEMENT_NEEDED);
        result.setOriginalPrompt(original);
        result.setOptimizedPrompt(original);
        result.setRejectionReason(reason);
        result.setRecommendAdoption(false);
        return result;
    }

    /**
     * 创建失败的优化结果。
     *
     * @param original 原始 Prompt
     * @param reason   失败原因
     * @return 优化结果
     */
    public static OptimizationResult failed(String original, String reason) {
        OptimizationResult result = new OptimizationResult();
        result.setStatus(Status.FAILED);
        result.setOriginalPrompt(original);
        result.setRejectionReason(reason);
        result.setRecommendAdoption(false);
        return result;
    }

    // ==================== 工具方法 ====================

    private String generateId() {
        return "opt_" + Instant.now().toEpochMilli();
    }

    /**
     * 添加中间变体。
     *
     * @param variant Prompt 变体
     */
    public void addVariant(PromptVariant variant) {
        variants.add(variant);
    }

    /**
     * 计算改进幅度。
     */
    public void calculateImprovement() {
        if (originalScore > 0) {
            this.improvement = optimizedScore - originalScore;
        }
    }

    /**
     * 判断是否有实质性改进。
     *
     * @param threshold 改进阈值
     * @return 改进幅度超过阈值返回 true
     */
    public boolean hasSignificantImprovement(double threshold) {
        return improvement >= threshold;
    }

    // ==================== 自定义 Setters（带校验逻辑） ====================

    /**
     * 设置中间变体列表（null 时回退为空列表）。
     */
    public void setVariants(List<PromptVariant> variants) {
        this.variants = variants != null ? variants : new ArrayList<>();
    }

    @Override
    public String toString() {
        return String.format("OptimizationResult{id='%s', status=%s, improvement=%.3f, recommend=%s}",
                id, status, improvement, recommendAdoption);
    }

    // ==================== 内部类：Prompt 变体 ====================

    /**
     * Prompt 变体，用于进化算法中的中间状态。
     */
    @Data
    @NoArgsConstructor
    public static class PromptVariant {
        private String prompt;
        private double score;
        private int generation;

        public PromptVariant(String prompt, double score, int generation) {
            this.prompt = prompt;
            this.score = score;
            this.generation = generation;
        }
    }
}
