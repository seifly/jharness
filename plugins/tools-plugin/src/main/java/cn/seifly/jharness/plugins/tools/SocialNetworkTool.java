package cn.seifly.jharness.plugins.tools;

import cn.seifly.jharness.plugin.framework.tools.Tool;
import cn.seifly.jharness.plugin.framework.tools.ToolException;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 社交网络工具，用于 Agent 间通信。
 *
 * 此工具使 Agent 能够加入 Agent 社交网络（例如 ClawdChat.ai）
 * 并与不同实例和平台的其他 Agent 进行通信。
 */
@Slf4j
public class SocialNetworkTool implements Tool {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static final int MAX_MESSAGE_LENGTH = 10000;    // 消息最大长度
    private static final long TIMEOUT_SECONDS = 30;         // 请求超时时间（秒）
    private static final String DEFAULT_ENDPOINT = "https://clawdchat.ai/api";
    private static final String DEFAULT_CHANNEL = "general";

    private final String endpoint;           // API 端点 URL
    private final String agentId;            // Agent 唯一标识符
    private final String apiKey;             // API 密钥
    private final OkHttpClient httpClient;   // HTTP 客户端

    /**
     * 社交网络工具构造函数。
     *
     * @param endpoint API 端点 URL（例如 https://clawdchat.ai/api）
     * @param agentId 此 Agent 的唯一标识符
     * @param apiKey 用于身份验证的 API 密钥
     */
    public SocialNetworkTool(String endpoint, String agentId, String apiKey) {
        this.endpoint = endpoint != null ? endpoint : DEFAULT_ENDPOINT;
        this.agentId = agentId;
        this.apiKey = apiKey;

        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build();

        log.info("SocialNetworkTool initialized: endpoint={}, agentId={}",
                this.endpoint, this.agentId != null ? this.agentId : "not-set");
    }

    @Override
    public String name() {
        return "social_network";
    }

    @Override
    public String description() {
        return "与 Agent 社交网络中的其他 Agent 通信。"
               + "操作：send（向特定 Agent）、broadcast（向通道）、query（Agent 目录）、status（网络状态）";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> params = new HashMap<>();
        params.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> actionParam = new HashMap<>();
        actionParam.put("type", "string");
        actionParam.put("description", "要执行的操作：send、broadcast、query、status");
        actionParam.put("enum", new String[]{"send", "broadcast", "query", "status"});
        properties.put("action", actionParam);

        Map<String, Object> toParam = new HashMap<>();
        toParam.put("type", "string");
        toParam.put("description", "目标 Agent ID（用于 'send' 操作）");
        properties.put("to", toParam);

        Map<String, Object> channelParam = new HashMap<>();
        channelParam.put("type", "string");
        channelParam.put("description", "通道名称（用于 'broadcast' 操作，默认：general）");
        properties.put("channel", channelParam);

        Map<String, Object> messageParam = new HashMap<>();
        messageParam.put("type", "string");
        messageParam.put("description", "消息内容");
        properties.put("message", messageParam);

        Map<String, Object> queryParam = new HashMap<>();
        queryParam.put("type", "string");
        queryParam.put("description", "查询字符串（用于 'query' 操作）");
        properties.put("query", queryParam);

        params.put("properties", properties);
        params.put("required", new String[]{"action"});

        return params;
    }

    @Override
    public String execute(Map<String, Object> args) throws ToolException {
        String action = (String) args.get("action");
        if (action == null || action.isEmpty()) {
            throw new IllegalArgumentException("操作参数是必需的");
        }

        // 验证 Agent 配置
        if (agentId == null || agentId.isEmpty()) {
            return "错误: Agent ID 未配置。请在 config.json 中设置 socialNetwork.agentId";
        }

        log.info("Social network action: action={}, agentId={}", action, agentId);

        return switch (action.toLowerCase()) {
            case "send" -> sendMessage(args);
            case "broadcast" -> broadcast(args);
            case "query" -> queryAgents(args);
            case "status" -> getNetworkStatus();
            default -> "错误: 未知操作 '" + action + "'。有效操作：send、broadcast、query、status";
        };
    }

    /**
     * 向特定 Agent 发送消息。
     */
    private String sendMessage(Map<String, Object> args) {
        String to = (String) args.get("to");
        String message = (String) args.get("message");

        validateRequiredParam(to, "to", "send");
        validateRequiredParam(message, "message", "send");

        message = truncateMessage(message);

        Map<String, Object> payload = buildPayload();
        payload.put("to", to);
        payload.put("message", message);

        return sendRequest("/messages/send", payload);
    }

    /**
     * 向通道广播消息。
     */
    private String broadcast(Map<String, Object> args) {
        String channel = (String) args.get("channel");
        String message = (String) args.get("message");

        channel = channel != null && !channel.isEmpty() ? channel : DEFAULT_CHANNEL;
        validateRequiredParam(message, "message", "broadcast");

        message = truncateMessage(message);

        Map<String, Object> payload = buildPayload();
        payload.put("channel", channel);
        payload.put("message", message);

        return sendRequest("/messages/broadcast", payload);
    }

    /**
     * 查询 Agent 目录。
     */
    private String queryAgents(Map<String, Object> args) {
        String query = (String) args.get("query");

        Map<String, Object> payload = buildPayload();
        if (query != null && !query.isEmpty()) {
            payload.put("query", query);
        }

        return sendRequest("/agents/query", payload);
    }

    /**
     * 获取网络状态。
     */
    private String getNetworkStatus() {
        Map<String, Object> payload = buildPayload();
        return sendRequest("/status", payload);
    }

    /**
     * 验证必需参数。
     */
    private void validateRequiredParam(String value, String paramName, String operation) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("对于 " + operation + " 操作，'" + paramName + "' 是必需的");
        }
    }

    /**
     * 截断过长的消息。
     */
    private String truncateMessage(String message) {
        if (message.length() > MAX_MESSAGE_LENGTH) {
            return message.substring(0, MAX_MESSAGE_LENGTH) + "... (已截断)";
        }
        return message;
    }

    /**
     * 构建基础请求载荷。
     */
    private Map<String, Object> buildPayload() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("from", agentId);
        payload.put("timestamp", System.currentTimeMillis());
        return payload;
    }

    /**
     * 向社交网络 API 发送 HTTP 请求。
     */
    private String sendRequest(String path, Map<String, Object> payload) {
        String url = endpoint + path;
        try {
            String jsonBody = objectMapper.writeValueAsString(payload);

            Request request = buildHttpRequest(url, jsonBody);

            log.info("Sending request to social network: url={}", url);

            try (Response response = httpClient.newCall(request).execute()) {
                return handleResponse(response, url);
            }
        } catch (Exception e) {
            log.error("Social network request exception: url={}, error={}", url, e.getMessage());
            return "无法连接到社交网络: " + e.getMessage();
        }
    }

    /**
     * 构建 HTTP 请求对象。
     */
    private Request buildHttpRequest(String url, String jsonBody) {
        RequestBody body = RequestBody.create(
            jsonBody,
            MediaType.parse("application/json; charset=utf-8")
        );

        Request.Builder requestBuilder = new Request.Builder()
            .url(url)
            .post(body)
            .header("User-Agent", "jharness/0.1.0");

        // 如果配置了 API 密钥，添加到请求头
        if (apiKey != null && !apiKey.isEmpty()) {
            requestBuilder.header("Authorization", "Bearer " + apiKey);
        }

        return requestBuilder.build();
    }

    /**
     * 处理 HTTP 响应。
     */
    private String handleResponse(Response response, String url) {
        String responseBody;
        try {
            responseBody = response.body() != null ? response.body().string() : "";
        } catch (java.io.IOException e) {
            log.error("Failed to read response body: url={}, error={}", url, e.getMessage());
            return "Error: Failed to read response body - " + e.getMessage();
        }

        if (!response.isSuccessful()) {
            log.warn("Social network request failed: url={}, status={}, body={}",
                    url, response.code(), responseBody);
            return String.format("Error: HTTP %d - %s", response.code(), responseBody);
        }

        log.info("Social network request succeeded: url={}, status={}", url, response.code());

        return responseBody.isEmpty() ? "成功" : responseBody;
    }
}
