package cn.seifly.jharness.plugin.framework.memory;

import cn.seifly.jharness.plugin.framework.service.Service;

import java.util.List;

/**
 * 记忆服务契约。
 *
 * <p>由 memory-plugin 在 {@code Plugin.start()} 内注册实现，消费方（如
 * workflow-plugin 的 ContextBuilder / SessionSummarizer）通过
 * {@link cn.seifly.jharness.plugin.framework.service.ServiceRegistry#get} 取得，
 * 不再编译期依赖 memory-plugin 的 {@code MemoryStore} 实现类。
 *
 * <p>凭证/路径注入沿用 #1 LlmService 的「消费方 push」模型：
 * 实现方在 {@code start()} 时以无参构造创建实例（未就绪），
 * 由 workflow 在加载配置后调用 {@link #init} 推入工作空间路径，
 * 之后 {@link #getMemoryContext} / {@link #addEntry} 才会真正读写持久化文件。
 *
 * <p>本契约仅暴露 workflow 当前实际调用的最小子集：
 * <ul>
 *   <li>{@link #getMemoryContext}：构建记忆上下文（ContextBuilder 的 MemorySection 使用）；</li>
 *   <li>{@link #addEntry}：写入一条结构化记忆（SessionSummarizer 在摘要完成后调用）。</li>
 * </ul>
 * MemoryEvolver 的 {@code evolve()} 当前在 workflow 内未被调用，故不入本契约；
 * 由 memory-plugin 自身或 heartbeat-plugin 触发，未来如需暴露可再扩展。
 */
public interface MemoryService extends Service {

    /**
     * 推入工作空间路径，完成内部存储目录与已有记忆条目的加载。
     *
     * <p>由 workflow 在配置加载后调用一次；重复调用以最后一次为准。
     * 调用前 {@link #getMemoryContext} / {@link #addEntry} 为 no-op。
     *
     * @param workspace 工作空间根路径
     */
    void init(String workspace);

    /**
     * 添加一条结构化记忆。
     *
     * @param content    记忆内容
     * @param importance 重要性评分 (0.0 ~ 1.0)
     * @param tags       标签列表
     * @param source     来源标识（如 session_summary / per_turn / evolution / user_explicit）
     */
    void addEntry(String content, double importance, List<String> tags, String source);

    /**
     * 获取格式化的记忆上下文，带 token 预算控制和相关性检索。
     *
     * @param currentMessage 当前用户消息，用于相关性匹配；可为 null
     * @param tokenBudget    记忆部分的 token 预算上限；&lt;=0 时使用默认预算
     * @return 格式化的记忆上下文；无可用内容时返回空字符串
     */
    String getMemoryContext(String currentMessage, int tokenBudget);

    /**
     * 无参版本，使用默认预算且不做相关性过滤。
     */
    String getMemoryContext();
}
