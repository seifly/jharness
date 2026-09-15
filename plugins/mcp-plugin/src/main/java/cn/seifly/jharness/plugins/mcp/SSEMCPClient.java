package cn.seifly.jharness.plugins.mcp;

import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 基于 SSE (Server-Sent Events) 的 MCP 客户端实现。
 *
 * MCP SSE 传输协议流程：
 * 1. 客户端 GET endpoint 建立 SSE 连接
 * 2. 服务器通过 SSE 发送 "endpoint" 事件，告知客户端 POST 消息的 URL
 * 3. 客户端通过 POST 到该 URL 发送 JSON-RPC 消息
 * 4. 服务器通过 SSE 流中的 "message" 事件返回 JSON-RPC 响应
 */
@Slf4j
public class SSEMCPClient extends AbstractMCPClient {

    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient httpClient;
    private final String sseEndpoint;
    private final String apiKey;

    private volatile Response sseResponse;
    private volatile Thread readerThread;
    /** 服务器通过 SSE "endpoint" 事件告知的消息接收 URL */
    private volatile String messageEndpoint;
    /** 用于等待 endpoint 事件到达 */
    private final CountDownLatch endpointLatch = new CountDownLatch(1);

    public SSEMCPClient(String sseEndpoint, String apiKey, int timeoutMs) {
        super(timeoutMs);
        this.sseEndpoint = sseEndpoint;
        this.apiKey = apiKey;

        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS) // SSE 流不设读超时
                .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build();
    }

    @Override
    public void connect() throws IOException {
        if (connected) {
            log.warn("Already connected to MCP server, endpoint: {}", sseEndpoint);
            return;
        }

        validateEndpoint(sseEndpoint);

        Request.Builder requestBuilder = new Request.Builder()
                .url(sseEndpoint)
                .header("Accept", "text/event-stream")
                .get();

        if (apiKey != null && !apiKey.isEmpty()) {
            requestBuilder.header("Authorization", "Bearer " + apiKey);
        }

        try {
            sseResponse = httpClient.newCall(requestBuilder.build()).execute();

            if (!sseResponse.isSuccessful()) {
                throw new IOException("SSE connection failed: HTTP " + sseResponse.code());
            }

            connected = true;

            // 启动后台线程读取 SSE 事件
            readerThread = new Thread(this::readSSEStream, "mcp-sse-reader-" + extractHost(sseEndpoint));
            readerThread.setDaemon(true);
            readerThread.start();

            // 等待服务器发送 endpoint 事件（最多等待超时时间）
            boolean received = endpointLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
            if (!received || messageEndpoint == null) {
                close();
                throw new IOException("Timeout waiting for SSE 'endpoint' event from server");
            }

            log.info("Connected to MCP server via SSE, sseEndpoint: {}, messageEndpoint: {}",
                    sseEndpoint, messageEndpoint);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            close();
            throw new IOException("Interrupted while connecting", e);
        } catch (IOException e) {
            log.error("Failed to connect to MCP server, endpoint: {}, error: {}",
                    sseEndpoint, e.getMessage());
            throw e;
        }
    }

    /**
     * 底层发送 JSON-RPC 消息（通过 POST 发送到服务器的消息端点）
     */
    @Override
    protected void doSend(String jsonMessage) throws IOException {
        if (messageEndpoint == null) {
            throw new IOException("Message endpoint not available (server has not sent 'endpoint' event)");
        }

        Request.Builder requestBuilder = new Request.Builder()
                .url(messageEndpoint)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(jsonMessage, JSON_MEDIA_TYPE));

        if (apiKey != null && !apiKey.isEmpty()) {
            requestBuilder.header("Authorization", "Bearer " + apiKey);
        }

        try (Response response = httpClient.newCall(requestBuilder.build()).execute()) {
            if (!response.isSuccessful()) {
                String responseBody = response.body() != null ? response.body().string() : "";
                throw new IOException("POST to MCP server failed: HTTP " + response.code() + " - " + responseBody);
            }
        }

        if (log.isDebugEnabled()) {
            log.debug("Sent message to MCP server, endpoint: {}", messageEndpoint);
        }
    }

    /**
     * 后台线程：持续读取 SSE 事件流
     */
    private void readSSEStream() {
        try {
            okio.BufferedSource source = sseResponse.body().source();

            String currentEventType = null;
            StringBuilder eventData = new StringBuilder();

            while (connected && !source.exhausted()) {
                String line = source.readUtf8Line();
                if (line == null) {
                    break;
                }

                if (line.startsWith("event: ")) {
                    currentEventType = line.substring(7).trim();
                } else if (line.startsWith("data: ")) {
                    eventData.append(line.substring(6));
                } else if (line.isEmpty() && eventData.length() > 0) {
                    // 空行表示事件结束，处理完整事件
                    handleSSEEvent(currentEventType, eventData.toString());
                    currentEventType = null;
                    eventData.setLength(0);
                }
            }

        } catch (IOException e) {
            if (connected) {
                log.error("SSE stream read error, error: {}", e.getMessage());
            }
        } finally {
            connected = false;
            // 通知所有等待中的请求连接已断开
            clearPendingRequests("SSE connection closed");
        }
    }

    /**
     * 处理单个 SSE 事件
     */
    private void handleSSEEvent(String eventType, String data) {
        if ("endpoint".equals(eventType)) {
            // 服务器告知消息端点 URL
            messageEndpoint = resolveEndpointUrl(data.trim());
            endpointLatch.countDown();
            if (log.isDebugEnabled()) {
                log.debug("Received message endpoint, endpoint: {}", messageEndpoint);
            }

        } else if ("message".equals(eventType) || eventType == null) {
            // JSON-RPC 消息（响应或通知）
            handleJsonRpcMessage(data);
        }
    }

    /**
     * 解析 endpoint URL：如果是相对路径则基于 SSE endpoint 解析
     */
    private String resolveEndpointUrl(String endpointPath) {
        if (endpointPath.startsWith("http://") || endpointPath.startsWith("https://")) {
            return endpointPath;
        }
        // 相对路径：基于 SSE endpoint 的 base URL 解析
        try {
            URI sseUri = URI.create(sseEndpoint);
            return sseUri.resolve(endpointPath).toString();
        } catch (Exception e) {
            log.warn("Failed to resolve endpoint URL, using as-is, path: {}, error: {}",
                    endpointPath, e.getMessage());
            return endpointPath;
        }
    }

    /**
     * 处理 JSON-RPC 消息
     */
    private void handleJsonRpcMessage(String data) {
        try {
            if (data.length() > MAX_RESPONSE_SIZE) {
                log.warn("Response too large, ignoring, size: {}, max: {}", data.length(), MAX_RESPONSE_SIZE);
                return;
            }

            MCPMessage message = objectMapper.readValue(data, MCPMessage.class);
            handleResponse(message);

        } catch (Exception e) {
            log.error("Failed to parse JSON-RPC message, error: {}", e.getMessage());
        }
    }

    /**
     * SSE 特有的关闭清理逻辑
     */
    @Override
    protected void doClose() {
        if (sseResponse != null) {
            sseResponse.close();
            sseResponse = null;
        }

        if (readerThread != null && readerThread.isAlive()) {
            try {
                readerThread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        log.info("Disconnected from MCP server, endpoint: {}", sseEndpoint);
    }

    private void validateEndpoint(String endpoint) throws IOException {
        if (endpoint == null || endpoint.isEmpty()) {
            throw new IOException("SSE endpoint cannot be empty");
        }
        if (!endpoint.startsWith("http://") && !endpoint.startsWith("https://")) {
            throw new IOException("SSE endpoint must start with http:// or https://");
        }
    }

    private String extractHost(String url) {
        try {
            return URI.create(url).getHost();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
