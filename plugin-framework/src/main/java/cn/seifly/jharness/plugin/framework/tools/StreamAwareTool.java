package cn.seifly.jharness.plugin.framework.tools;

import cn.seifly.jharness.plugin.framework.llm.LlmService;

/**
 * 流式输出感知工具接口。
 *
 * <p>由需要接收 LLM 流式 token 片段的 {@link Tool} 实现类实现。
 * 实现此接口的工具会在执行过程中被注入流式回调，
 * 用于实时输出中间结果。
 *
 * <p>提升到 SDK（plugin-framework）后，任意插件的 Tool 实现均可实现本接口，
 * 消费方（如 workflow-plugin 的 ReActExecutor）通过 {@code instanceof} 检查并回调，
 * 不再编译期依赖 tools-plugin。
 */
public interface StreamAwareTool {

    /**
     * 设置流式回调。
     *
     * @param callback 流式回调，可为 null
     */
    void setStreamCallback(LlmService.EnhancedStreamCallback callback);
}
