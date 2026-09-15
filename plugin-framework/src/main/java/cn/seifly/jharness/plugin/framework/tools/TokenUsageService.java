package cn.seifly.jharness.plugin.framework.tools;

import cn.seifly.jharness.plugin.framework.service.Service;

/**
 * LLM Token 消耗记录服务契约。
 *
 * <p>由 tools-plugin 在 {@code Plugin.start()} 内注册实现，消费方（如 workflow-plugin
 * 的 ReActExecutor）通过 {@link cn.seifly.jharness.plugin.framework.service.ServiceRegistry#get}
 * 取得，不再编译期依赖 tools-plugin 的 {@code TokenUsageStore} 实现类。
 *
 * <p>凭证/路径注入沿用 #1 LlmService 的「消费方 push」模型：
 * 实现方在 {@code start()} 时以无参构造创建实例（{@code isReady() == false}），
 * 由 workflow 在加载配置后调用 {@link #init} 推入工作空间路径，
 * 之后 {@link #record} 才会真正持久化。
 *
 * <p>设计动机：workspace 路径来自 workflow 的 Config，tools-plugin 自身不持有 Config；
 * 通过 push 模型避免插件间共享配置对象的耦合。
 */
public interface TokenUsageService extends Service {

    /**
     * 推入工作空间路径，完成内部存储目录初始化。
     *
     * <p>由 workflow 在配置加载后调用一次；重复调用以最后一次为准。
     * 调用前 {@link #record} 为 no-op。
     *
     * @param workspace 工作空间根路径
     */
    void init(String workspace);

    /**
     * 记录一次 LLM 调用的 token 消耗。
     *
     * @param provider           提供商名称（如 dashscope、openai）
     * @param model              模型名称
     * @param promptTokens       输入 token 数
     * @param completionTokens   输出 token 数
     */
    void record(String provider, String model, int promptTokens, int completionTokens);
}
