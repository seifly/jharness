package cn.seifly.jharness.plugins.ui.controller;

import cn.seifly.jharness.plugin.framework.service.AgentRuntimeService;
import cn.seifly.jharness.plugin.framework.service.ServiceRegistry;
import cn.seifly.jharness.plugin.framework.service.StreamCallback;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 聊天 API 控制器
 *
 * <p>提供聊天、流式聊天、中断任务和状态查询功能。
 * 聊天能力由 workflow-plugin 注册的 {@link AgentRuntimeService} 提供，
 * 经 {@link ServiceRegistry} 获取：运行时服务未就绪时返回 503（流式返回 SSE [ERROR]），
 * 消息为空时返回 400（流式返回 SSE [ERROR]）。
 */
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "${jclaw.gateway.cors-origin:*}", allowedHeaders = "*", methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.OPTIONS})
@Slf4j
public class ChatController {

    private static final String DEFAULT_SESSION_ID = "web:default";

    /** Agent 运行时服务未就绪（workflow-plugin 未启动或注册失败）时的提示 */
    private static final String RUNTIME_UNAVAILABLE_MESSAGE = "Agent 运行时未就绪，请确认 workflow-plugin 已启动";

    /** 请求消息为空时的提示 */
    private static final String EMPTY_MESSAGE = "消息不能为空";

    private final ExecutorService executor = Executors.newCachedThreadPool();

    @Autowired
    private ServiceRegistry services;

    /**
     * 获取已注册的 AgentRuntimeService（由 workflow-plugin 在 start() 时注册）；
     * 若 workflow-plugin 未启动或注册失败，返回 null。
     */
    private AgentRuntimeService runtime() {
        return services.get(AgentRuntimeService.class).orElse(null);
    }

    /**
     * 普通聊天请求
     *
     * @param request 包含 message 和 sessionId 的请求体
     * @return 聊天响应
     */
    @PostMapping("/chat")
    public ResponseEntity<Map<String, Object>> chat(@RequestBody Map<String, String> request) {
        String sessionId = request.getOrDefault("sessionId", DEFAULT_SESSION_ID);
        String message = request.get("message");

        if (message == null || message.isEmpty()) {
            return ResponseEntity.badRequest().body(buildResult(sessionId, EMPTY_MESSAGE));
        }
        AgentRuntimeService rt = runtime();
        if (rt == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(buildResult(sessionId, RUNTIME_UNAVAILABLE_MESSAGE));
        }

        return ResponseEntity.ok(buildResult(sessionId, rt.processDirect(message, sessionId, null)));
    }

    /**
     * 流式聊天请求（SSE）
     *
     * 在独立线程中执行 Agent 流式调用，通过 {@link StreamCallback} 桥接为 SSE 事件；
     * 消息为空或运行时未就绪时推送 [ERROR] 事件后结束流。
     *
     * @param request 包含 message、sessionId 和 images 的请求体
     * @return SseEmitter 用于流式响应
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestBody Map<String, Object> request) {
        String sessionId = (String) request.getOrDefault("sessionId", DEFAULT_SESSION_ID);
        String message = (String) request.get("message");
        // 解析图片列表（多模态支持）
        List<String> images = parseImages(request);

        // 创建 SseEmitter，设置超时时间（5分钟）
        SseEmitter emitter = new SseEmitter(300000L);

        AgentRuntimeService rt = runtime();
        if (message == null || message.isEmpty()) {
            executor.execute(() -> emitSSEErrorAndComplete(emitter, EMPTY_MESSAGE));
            return emitter;
        }
        if (rt == null) {
            executor.execute(() -> emitSSEErrorAndComplete(emitter, RUNTIME_UNAVAILABLE_MESSAGE));
            return emitter;
        }

        executor.execute(() -> {
            try {
                // 把 SDK StreamCallback 桥接到 SseEmitter：
                // onChunk → 写入 data 事件；onComplete → 写入 [DONE] 并结束；
                // onError → 写入错误事件并以错误结束 emitter
                rt.processDirectStream(message, sessionId, images, new StreamCallback() {
                    @Override
                    public void onChunk(String chunk) {
                        try {
                            writeSSEData(emitter, chunk);
                        } catch (IOException e) {
                            log.warn("SSE 写入 chunk 失败: error={}", e.getMessage());
                        }
                    }

                    @Override
                    public void onComplete(String fullResponse) {
                        try {
                            writeSSEDone(emitter);
                            emitter.complete();
                        } catch (IOException e) {
                            log.warn("SSE 写入 [DONE] 失败: error={}", e.getMessage());
                            emitter.completeWithError(e);
                        }
                    }

                    @Override
                    public void onError(Throwable cause) {
                        try {
                            writeSSEError(emitter, cause.getMessage());
                        } catch (IOException e) {
                            log.warn("SSE 写入错误事件失败: error={}", e.getMessage());
                        }
                        emitter.completeWithError(cause);
                    }
                });
            } catch (Exception e) {
                log.error("聊天流处理错误: error_type={}, error_message={}, session_id={}",
                        e.getClass().getSimpleName(), e.getMessage(), sessionId, e);
                try {
                    writeSSEError(emitter, e.getMessage());
                } catch (IOException ioException) {
                    log.error("写入 SSE 错误消息失败: error_type={}, error_message={}",
                            ioException.getClass().getSimpleName(), ioException.getMessage(), ioException);
                }
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    /**
     * 中断当前正在执行的 LLM 任务
     *
     * @return 中断结果
     */
    @PostMapping("/chat/abort")
    public ResponseEntity<Map<String, Object>> abortChat() {
        AgentRuntimeService rt = runtime();
        if (rt == null) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", RUNTIME_UNAVAILABLE_MESSAGE);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(result);
        }
        boolean aborted = rt.abortCurrentTask();
        Map<String, Object> result = new HashMap<>();
        result.put("success", aborted);
        result.put("message", aborted ? "已发送中断信号" : "当前无正在执行的任务");
        return ResponseEntity.ok(result);
    }

    /**
     * 查询当前是否有任务正在运行
     *
     * @return 运行状态
     */
    @GetMapping("/chat/status")
    public ResponseEntity<Map<String, Object>> chatStatus() {
        Map<String, Object> result = new HashMap<>();
        AgentRuntimeService rt = runtime();
        result.put("running", rt != null && rt.isTaskRunning());
        return ResponseEntity.ok(result);
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 从请求中解析图片路径列表。
     * 支持 images 字段为字符串数组（图片路径）。
     */
    @SuppressWarnings("unchecked")
    private List<String> parseImages(Map<String, Object> request) {
        List<String> images = new ArrayList<>();
        Object imagesObj = request.get("images");

        if (imagesObj instanceof List<?>) {
            List<?> imagesList = (List<?>) imagesObj;
            for (Object imgObj : imagesList) {
                if (imgObj instanceof String) {
                    String imgPath = (String) imgObj;
                    if (imgPath != null && !imgPath.isEmpty()) {
                        images.add(imgPath);
                    }
                }
            }
        }

        return images.isEmpty() ? null : images;
    }

    /**
     * 组装统一响应体：{@code response} + {@code sessionId}。
     */
    private Map<String, Object> buildResult(String sessionId, String response) {
        Map<String, Object> result = new HashMap<>();
        result.put("response", response);
        result.put("sessionId", sessionId);
        return result;
    }

    /**
     * 将文本作为 SSE data 事件发送。
     */
    private void writeSSEData(SseEmitter emitter, String data) throws IOException {
        emitter.send(SseEmitter.event()
                .data(data)
                .build());
    }

    /**
     * 向客户端发送 [DONE] 信号，标志流式输出结束。
     */
    private void writeSSEDone(SseEmitter emitter) throws IOException {
        emitter.send(SseEmitter.event()
                .data("[DONE]")
                .build());
    }

    /**
     * 向客户端发送错误事件，内容为错误信息的转义字符串。
     */
    private void writeSSEError(SseEmitter emitter, String errorMessage) throws IOException {
        emitter.send(SseEmitter.event()
                .data("[ERROR] " + escapeSSE(errorMessage))
                .build());
    }

    /**
     * 发送 SSE [ERROR] 事件后正常结束流，用于参数校验 / 运行时未就绪等前置失败场景。
     */
    private void emitSSEErrorAndComplete(SseEmitter emitter, String errorMessage) {
        try {
            writeSSEError(emitter, errorMessage);
            emitter.complete();
        } catch (IOException e) {
            log.warn("SSE 写入错误事件失败: error={}", e.getMessage());
            emitter.completeWithError(e);
        }
    }

    /**
     * 将内容中的换行符替换为 SSE 安全的占位符，防止协议解析错误。
     */
    private String escapeSSE(String content) {
        if (content == null) return "";
        return content.replace("\n", "\ndata: ");
    }
}
