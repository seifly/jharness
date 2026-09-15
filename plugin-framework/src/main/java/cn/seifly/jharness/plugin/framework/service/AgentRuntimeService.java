package cn.seifly.jharness.plugin.framework.service;

import java.util.List;

/**
 * Agent 运行期服务契约。
 *
 * <p>覆盖聊天、流式聊天、任务中断、运行状态查询与模型热重载等运行期行为。
 * 由 workflow-plugin 提供（在 {@code WorkflowPlugin.start()} 中注册），
 * 由 ui-plugin（ChatController / ConfigController）等消费方通过
 * {@link ServiceRegistry#get ServiceRegistry} 获取。
 *
 * <p>覆盖范围：
 * <ul>
 *   <li>{@link #processDirect} —— {@code POST /api/chat}</li>
 *   <li>{@link #processDirectStream} —— {@code POST /api/chat/stream}</li>
 *   <li>{@link #abortCurrentTask} —— {@code POST /api/chat/abort}</li>
 *   <li>{@link #isTaskRunning} —— {@code GET /api/chat/status}</li>
 *   <li>{@link #reloadModel} —— {@code PUT /api/config/model} 保存后的热重载</li>
 * </ul>
 */
public interface AgentRuntimeService extends Service {

    /**
     * 同步对话：发送 {@code content} 到 Agent，等待完整回复。
     *
     * @param content    用户消息文本
     * @param sessionKey 会话标识（如 {@code "web:<uuid>"}）；为 null 时由实现方自行决定
     * @param images     多模态图片路径列表，无图片传 {@code null}
     * @return Agent 的完整回复文本；Provider 未配置时返回提示性字符串（非异常）
     */
    String processDirect(String content, String sessionKey, List<String> images);

    /**
     * 流式对话：将 Agent 生成的 token 片段实时通过 {@code callback} 回传。
     *
     * <p>调用方负责把 {@link StreamCallback} 转换为 SSE / WebSocket 等传输协议；
     * 实现方负责调用 {@link StreamCallback#onComplete} 或 {@link StreamCallback#onError}
     * 标记结束。
     *
     * @param content    用户消息文本
     * @param sessionKey 会话标识
     * @param images     多模态图片路径列表，无图片传 {@code null}
     * @param callback   流式回调，由调用方提供
     */
    void processDirectStream(String content, String sessionKey,
                            List<String> images, StreamCallback callback);

    /**
     * 请求中断当前正在执行的任务（若有）。
     *
     * <p>异步语义：仅发出中断信号，不等待任务真正停止；调用后仍可通过
     * {@link #isTaskRunning()} 轮询确认。
     *
     * @return 若存在任务被请求中断返回 true；当前无任务返回 false
     */
    boolean abortCurrentTask();

    /**
     * 是否有任务正在执行。
     */
    boolean isTaskRunning();

    /**
     * 模型 / Provider 配置变更后的热重载。
     *
     * <p>典型场景：ConfigController 将新配置写入 Redis 后调用本方法，
     * 实现方应先把 Redis 配置同步进内存 Config，再触发 Provider 重建。
     *
     * @return true 表示重载成功并已切换到新 Provider；false 表示未配置 / 无效 / 未变化
     */
    boolean reloadModel();
}
