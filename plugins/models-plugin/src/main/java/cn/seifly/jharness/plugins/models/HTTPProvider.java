package cn.seifly.jharness.plugins.models;

import cn.seifly.jharness.plugin.framework.llm.LLMResponse;
import cn.seifly.jharness.plugin.framework.llm.LlmService;
import cn.seifly.jharness.plugin.framework.llm.Message;
import cn.seifly.jharness.plugin.framework.llm.ToolDefinition;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import okhttp3.ConnectionPool;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 基于 HTTP 的 LLM Provider 实现（OpenAI 兼容接口）。
 *
 * <p>实现 SDK 契约 {@link LlmService}，由 models-plugin 在 {@link ModelsPlugin#start()}
 * 中注册到 {@code ServiceRegistry}，供 workflow-plugin 等消费方通过
 * {@code services.get(LlmService.class, providerName)} 获取，不再被消费方直接 {@code new}。
 *
 * <p>凭证注入采用「消费方 push」模型：消费方调用 {@link #reload} 把
 * {@code providerName / apiKey / apiBase} 推入，本类据此热替换底层连接参数；
 * {@link #isReady} 在 {@code apiBase} 就绪后返回 true。
 *
 * <p>支持 OpenAI 兼容的 API 接口，包括但不限于：
 * OpenAI、Anthropic、OpenRouter、智谱 AI、阿里云 DashScope、Ollama、Gemini 等。
 */
@Slf4j
public class HTTPProvider implements LlmService {

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    // 超时配置
    private static final int CONNECT_TIMEOUT_SECONDS = 30;
    private static final int READ_TIMEOUT_SECONDS = 120;
    private static final int WRITE_TIMEOUT_SECONDS = 30;

    // 其他常量
    private static final int MAX_ERROR_RESPONSE_LENGTH = 500;
    private static final String CHAT_COMPLETIONS_ENDPOINT = "/chat/completions";
    private static final String AUTHORIZATION_PREFIX = "Bearer ";

    // 凭证字段：volatile，支持 reload 热替换
    private volatile String apiKey;
    private volatile String apiBase;
    private volatile String name;

    private final OkHttpClient httpClient;
    private final LLMRequestBuilder requestBuilder;
    private final StreamResponseParser responseParser;

    public HTTPProvider(String apiKey, String apiBase) {
        this(apiKey, apiBase, "unknown");
    }

    public HTTPProvider(String apiKey, String apiBase, String name) {
        this.apiKey = apiKey;
        this.apiBase = apiBase;
        this.name = name != null ? name : "unknown";
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .connectionPool(new ConnectionPool(5, 5, TimeUnit.MINUTES))
                .build();
        this.requestBuilder = new LLMRequestBuilder(objectMapper);
        this.responseParser = new StreamResponseParser(objectMapper);
    }

    @Override
    public LLMResponse chatStream(List<Message> messages, List<ToolDefinition> tools, String model,
                                  Map<String, Object> options, StreamCallback callback) {
        if (apiBase == null || apiBase.isEmpty()) {
            throw new IllegalStateException("API base not configured");
        }

        // 构建请求体并启用流式输出
        ObjectNode requestBody;
        try {
            requestBody = requestBuilder.buildRequestBody(messages, tools, model, options);
        } catch (Exception e) {
            throw new LLMException("构建请求体失败", e);
        }
        requestBody.put("stream", true);
        // 显式要求在流式响应的最后一个 chunk 中返回 usage 信息（OpenAI 兼容 API 标准）
        ObjectNode streamOptions = requestBody.putObject("stream_options");
        streamOptions.put("include_usage", true);

        String requestJson;
        try {
            requestJson = requestBuilder.toJson(requestBody);
        } catch (JsonProcessingException e) {
            throw new LLMException("序列化请求失败", e);
        }
        if (log.isDebugEnabled()) {
            log.debug("LLM stream request: {}", Map.of(
                    "model", model,
                    "messages_count", messages.size(),
                    "tools_count", tools != null ? tools.size() : 0
            ));
        }

        log.info("🔍 LLM Stream Request Full Body: {}", Map.of(
                "model", model,
                "request_json", requestJson.length() > 2000
                        ? requestJson.substring(0, 2000) + "..."
                        : requestJson
        ));

        if (tools != null && !tools.isEmpty()) {
            log.info("🔍 LLM Stream Request with Tools: {}", Map.of(
                    "model", model,
                    "tools_count", tools.size(),
                    "tool_names", tools.stream().map(t -> t.getFunction().getName()).toList()
            ));
        }

        // 构建并执行 HTTP 请求
        Request request = buildHttpRequest(requestJson);
        try (Response response = httpClient.newCall(request).execute()) {
            validateResponse(response, model);
            return responseParser.parseStreamResponse(response.body().source(), callback);
        } catch (IOException e) {
            log.error("LLM 流式请求执行失败: {}", Map.of(
                    "model", model,
                    "api_base", apiBase,
                    "error_type", e.getClass().getSimpleName(),
                    "error_message", e.getMessage()
            ), e);
            throw new LLMException("执行请求失败: " + e.getClass().getSimpleName() + " - " + e.getMessage(), e);
        }
    }

    @Override
    public LLMResponse chat(List<Message> messages, List<ToolDefinition> tools, String model,
                           Map<String, Object> options) {
        if (apiBase == null || apiBase.isEmpty()) {
            throw new IllegalStateException("API base not configured");
        }

        // 构建请求体
        ObjectNode requestBody;
        try {
            requestBody = requestBuilder.buildRequestBody(messages, tools, model, options);
        } catch (Exception e) {
            throw new LLMException("构建请求体失败", e);
        }
        String requestJson;
        try {
            requestJson = requestBuilder.toJson(requestBody);
        } catch (JsonProcessingException e) {
            throw new LLMException("序列化请求失败", e);
        }

        if (log.isDebugEnabled()) {
            log.debug("LLM request: {}", Map.of(
                    "model", model,
                    "messages_count", messages.size(),
                    "tools_count", tools != null ? tools.size() : 0,
                    "request_length", requestJson.length()
            ));
        }

        log.info("🔍 LLM Request Full Body: {}", Map.of(
                "model", model,
                "request_json", requestJson.length() > 2000
                        ? requestJson.substring(0, 2000) + "..."
                        : requestJson
        ));

        if (tools != null && !tools.isEmpty()) {
            log.info("🔍 LLM Request with Tools: {}", Map.of(
                    "model", model,
                    "tools_count", tools.size(),
                    "tool_names", tools.stream().map(t -> t.getFunction().getName()).toList()
            ));
        }

        // 构建并执行 HTTP 请求
        Request request = buildHttpRequest(requestJson);
        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            validateResponse(response, responseBody, model);

            log.info("🔍 LLM Raw Response: {}", Map.of(
                    "model", model,
                    "response_length", responseBody.length(),
                    "response_preview", responseBody.length() > 500
                            ? responseBody.substring(0, 500) + "..."
                            : responseBody
            ));

            return responseParser.parseResponse(responseBody);
        } catch (IOException e) {
            log.error("LLM 非流式请求执行失败: {}", Map.of(
                    "model", model,
                    "api_base", apiBase,
                    "error_type", e.getClass().getSimpleName(),
                    "error_message", e.getMessage()
            ), e);
            throw new LLMException("执行请求失败: " + e.getClass().getSimpleName() + " - " + e.getMessage(), e);
        }
    }

    /**
     * 构建 HTTP 请求对象。
     */
    private Request buildHttpRequest(String requestJson) {
        String url = apiBase + CHAT_COMPLETIONS_ENDPOINT;
        RequestBody body = RequestBody.create(requestJson, JSON);
        Request.Builder requestBuilder = new Request.Builder()
                .url(url)
                .post(body)
                .header("Content-Type", "application/json");

        if (apiKey != null && !apiKey.isEmpty()) {
            requestBuilder.header("Authorization", AUTHORIZATION_PREFIX + apiKey);
        }

        return requestBuilder.build();
    }

    /**
     * 验证 HTTP 响应状态（流式请求）。
     */
    private void validateResponse(Response response, String model) throws IOException {
        if (response.isSuccessful()) {
            return;
        }

        String errorBody = response.body() != null ? response.body().string() : "";
        String errorPreview = errorBody.substring(0, Math.min(MAX_ERROR_RESPONSE_LENGTH, errorBody.length()));

        log.error("LLM API error: {}", Map.of(
                "model", model,
                "api_base", apiBase,
                "status_code", response.code(),
                "response", errorPreview
        ));

        throw new IOException("LLM API error (model=" + model + ", status=" + response.code() + "): " + errorBody);
    }

    /**
     * 验证 HTTP 响应状态（非流式请求，带响应体参数）。
     */
    private void validateResponse(Response response, String responseBody, String model) throws IOException {
        if (response.isSuccessful()) {
            return;
        }

        String errorPreview = responseBody.substring(0, Math.min(MAX_ERROR_RESPONSE_LENGTH, responseBody.length()));

        log.error("LLM API error: {}", Map.of(
                "model", model,
                "api_base", apiBase,
                "status_code", response.code(),
                "response", errorPreview
        ));

        throw new IOException("LLM API error (model=" + model + ", status=" + response.code() + "): " + responseBody);
    }

    @Override
    public String getDefaultModel() {
        return "";
    }

    @Override
    public String getName() {
        return name;
    }

    /**
     * 热重载凭证：消费方（workflow-plugin）在配置变更后把新的
     * {@code providerName / apiKey / apiBase} 推入，本类据此替换连接参数，
     * 复用已有 {@link OkHttpClient} / {@link LLMRequestBuilder} / {@link StreamResponseParser}。
     */
    @Override
    public void reload(String providerName, String apiKey, String apiBase) {
        this.name = providerName != null ? providerName : this.name;
        this.apiKey = apiKey;
        this.apiBase = apiBase;
        log.info("HTTPProvider reloaded: provider={}, api_base={}", this.name, this.apiBase);
    }

    @Override
    public boolean isReady() {
        return apiBase != null && !apiBase.isEmpty();
    }
}
