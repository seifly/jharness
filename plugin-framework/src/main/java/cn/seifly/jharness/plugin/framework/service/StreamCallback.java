package cn.seifly.jharness.plugin.framework.service;

/**
 * 流式输出回调契约（SDK 共享）。
 *
 * <p>用于跨插件的流式响应场景，如 {@link AgentRuntimeService#processDirectStream}
 * 把 Agent 推送的 token 片段实时回传给 HTTP 控制器（SSE）。
 *
 * <p>注意：本接口必须定义在 SDK（plugin-framework）中，使 ui-plugin 与 workflow-plugin
 * 在各自的 ClassLoader 下都能加载到同一类型；workflow-plugin 内部再适配为本插件
 * 内部的 {@code LlmService.StreamCallback}。
 */
public interface StreamCallback {

    /**
     * 收到一个文本片段。可能多次调用，顺序保证与生成顺序一致。
     *
     * @param chunk 文本片段，永不为 null（空串表示分隔）
     */
    void onChunk(String chunk);

    /**
     * 流式输出正常结束。{@code fullResponse} 为最终拼装的完整回复文本。
     * 调用本方法后不会再有 {@link #onChunk} 调用。
     *
     * @param fullResponse 完整回复文本
     */
    void onComplete(String fullResponse);

    /**
     * 流式过程中出现错误。调用本方法后不会再有 {@link #onChunk} / {@link #onComplete} 调用。
     *
     * @param cause 错误原因
     */
    void onError(Throwable cause);
}
