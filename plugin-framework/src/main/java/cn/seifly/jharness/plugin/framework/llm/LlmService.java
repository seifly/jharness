package cn.seifly.jharness.plugin.framework.llm;

import cn.seifly.jharness.plugin.framework.service.Service;

import java.util.List;
import java.util.Map;

/**
 * LLM 服务契约（SDK 共享）。
 *
 * <p>由 Provider 插件实现并注册到
 * {@link cn.seifly.jharness.plugin.framework.service.ServiceRegistry ServiceRegistry}，
 * 消费方（如 workflow-plugin 的 ProviderManager / ReActExecutor）通过
 * {@code services.get(LlmService.class, providerName)} 获取，不再直接 {@code new} 具体 Provider，
 * 从而解除 workflow-plugin 对 models-plugin 实现类的编译期依赖。
 *
 * <p>凭证注入采用「消费方 push」模型：workflow-plugin 读取配置后调用 {@link #reload}
 * 把 {@code providerName / apiKey / apiBase} 推给实现，实现内部据此重建底层 HTTP 客户端；
 * 未来 DeepSeek / Anthropic 等独立成插件时可改为自读配置，{@link #reload} 退化为 no-op。
 *
 * <p>本接口及其引用的所有类型（{@link Message} / {@link ToolDefinition} / {@link ToolCall} /
 * {@link LLMResponse} / {@link StreamEvent}）均定义在 SDK（plugin-framework）中，
 * 由宿主 ClassLoader 加载，确保跨插件 ClassLoader 一致、不会出现
 * {@code ClassCastException}。
 *
 * <p>等价契约说明：本接口沿用 {@code chat / chatStream} 方法签名（而非
 * {@code complete(Request)→Completion}），目的是把 #1 重构控制在纯「Provider 选择解耦」范围内，
 * 避免一次性改写 ReActExecutor / SessionSummarizer 等约 15 个消费类的调用点。
 * 后续若需进一步细粒度化，可在本接口之上再包一层 {@code Request / Completion}。
 */
public interface LlmService extends Service {

    /**
     * 流式输出回调接口（基础版本，仅支持文本内容）。
     */
    @FunctionalInterface
    interface StreamCallback {
        /**
         * 当接收到流式内容块时调用。
         *
         * @param content 内容块
         */
        void onChunk(String content);
    }

    /**
     * 增强的流式输出回调接口，支持多种类型的事件。
     *
     * <p>可用于输出工具调用、子代理执行、多 Agent 协同等过程信息。
     */
    interface EnhancedStreamCallback extends StreamCallback {
        /**
         * 当接收到流式事件时调用。
         *
         * @param event 流式事件
         */
        void onEvent(StreamEvent event);

        /**
         * 默认实现：将内容事件转换为普通 chunk 调用。
         */
        @Override
        default void onChunk(String content) {
            onEvent(StreamEvent.content(content));
        }

        /**
         * 创建一个包装器，将基础 StreamCallback 包装为 EnhancedStreamCallback。
         * 对于非内容事件，会调用 format() 方法格式化后输出。
         */
        static EnhancedStreamCallback wrap(StreamCallback callback) {
            if (callback == null) return null;
            if (callback instanceof EnhancedStreamCallback enhanced) {
                return enhanced;
            }
            return event -> {
                // 对于内容事件，直接输出内容
                if (event.getType() == StreamEvent.EventType.CONTENT) {
                    callback.onChunk(event.getContent());
                } else {
                    // 对于其他事件，格式化后输出
                    callback.onChunk(event.format());
                }
            };
        }
    }

    /**
     * 发送对话完成请求。
     *
     * @param messages 对话消息列表
     * @param tools    可用工具列表（可为null）
     * @param model    使用的模型
     * @param options  额外选项（temperature、max_tokens等）
     * @return LLM响应结果
     */
    LLMResponse chat(List<Message> messages, List<ToolDefinition> tools, String model, Map<String, Object> options);

    /**
     * 发送流式对话完成请求。
     *
     * @param messages 对话消息列表
     * @param tools    可用工具列表（可为null）
     * @param model    使用的模型
     * @param options  额外选项（temperature、max_tokens等）
     * @param callback 流式内容回调
     * @return 完整的LLM响应结果（用于获取工具调用等信息）
     */
    LLMResponse chatStream(List<Message> messages, List<ToolDefinition> tools, String model, Map<String, Object> options, StreamCallback callback);

    /**
     * 获取该提供者的默认模型。
     */
    String getDefaultModel();

    /**
     * 获取该提供者的名称（如 dashscope、openai、ollama 等）。
     */
    String getName();

    /**
     * 热重载凭证。
     *
     * <p>消费方（workflow-plugin）在配置变更后把新的 {@code providerName / apiKey / apiBase}
     * 推入；实现应据此重建底层 HTTP 客户端或更新配置。自读配置的实现可退化为 no-op。
     *
     * @param providerName provider 名称
     * @param apiKey       API Key（本地服务可为 null/空）
     * @param apiBase      API Base URL
     */
    void reload(String providerName, String apiKey, String apiBase);

    /**
     * 是否已就绪（凭证已注入且可发起请求）。
     */
    boolean isReady();
}
