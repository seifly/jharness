package cn.seifly.jharness.plugins.channel.channels;

import cn.seifly.jharness.plugin.framework.bus.OutboundMessage;
import cn.seifly.jharness.plugins.channel.BaseChannel;
import cn.seifly.jharness.plugins.channel.ChannelException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

import cn.seifly.jharness.plugin.framework.bus.MessageBus;
import cn.seifly.jharness.plugin.framework.config.ChannelsConfig;
import lombok.extern.slf4j.Slf4j;
import cn.seifly.jharness.plugin.framework.security.SSLUtils;
import cn.seifly.jharness.plugin.framework.StringUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Slf4j(topic = "wecom")
public class WeComChannel extends BaseChannel {

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");

    private static final String BOT_WS_URL = "wss://openws.work.weixin.qq.com";

    private static final int MAX_RECONNECT_ATTEMPTS = 10;
    private static final long INITIAL_RECONNECT_DELAY_MS = 1000L;
    private static final long MAX_RECONNECT_DELAY_MS = 60000L;

    private final ChannelsConfig.WeComConfig config;
    private final OkHttpClient httpClient;
    private final Map<String, String> sessionResponseUrls = new ConcurrentHashMap<>();

    private volatile boolean wsConnected = false;
    private WebSocket webSocket;
    private int reconnectAttempts = 0;

    public WeComChannel(ChannelsConfig.WeComConfig config, MessageBus bus) {
        super("wecom", bus, config.getAllowFrom());
        this.config = config;
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .pingInterval(25, TimeUnit.SECONDS)
            .sslSocketFactory(SSLUtils.getDefaultSSLSocketFactory(), SSLUtils.getDefaultTrustManager())
            .build();
    }

    @Override
    public void start() {
        log.info("Starting WeCom channel...");

        if (config.getBotId() == null || config.getBotId().isEmpty()) {
            throw new ChannelException("WeCom Bot ID is empty");
        }
        if (config.getSecret() == null || config.getSecret().isEmpty()) {
            throw new ChannelException("WeCom Bot Secret is empty");
        }

        try {
            log.info("Connecting to WeCom WebSocket...");
            connectWebSocketDirectly();
        } catch (Exception e) {
            log.error("Failed to start Bot mode: error={}", e.getMessage());
            throw new ChannelException("Failed to start Bot mode: " + e.getMessage(), e);
        }

        setRunning(true);
        log.info("WeCom channel started successfully");
    }

    private void connectWebSocketDirectly() {
        log.info("Connecting to WeCom WebSocket (official way): botId={}", config.getBotId());
        
        connectWebSocketWithUrl(BOT_WS_URL);
    }

    private void connectWebSocketWithUrl(String wsUrl) {
        log.info("Creating WebSocket connection to: url={}", wsUrl);
        
        Request request = new Request.Builder()
            .url(wsUrl)
            .addHeader("User-Agent", "aibot-node-sdk/1.0 (Java)")
            .build();

        log.info("WebSocket request headers: headers={}", request.headers().toString());

        webSocket = httpClient.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                if (log.isDebugEnabled()) {
                    log.debug("WeCom WebSocket connected, sending auth frame: code={}, message={}",
                        response.code(), response.message());
                }
                
                try {
                    ObjectNode authFrame = objectMapper.createObjectNode();
                    authFrame.put("cmd", "aibot_subscribe");
                    ObjectNode headers = authFrame.putObject("headers");
                    headers.put("req_id", generateReqId("aibot_subscribe"));
                    ObjectNode body = authFrame.putObject("body");
                    body.put("bot_id", config.getBotId());
                    body.put("secret", config.getSecret());
                    
                    String authJson = objectMapper.writeValueAsString(authFrame);
                    if (log.isDebugEnabled()) {
                        log.debug("Sending auth frame: authFrame={}",
                            authJson.replace(config.getSecret(), "***"));
                    }
                    
                    webSocket.send(authJson);
                } catch (Exception e) {
                    log.error("Failed to send auth frame: error={}", e.getMessage());
                }
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                if (log.isDebugEnabled()) {
                    log.debug("WebSocket message received: message={}", text);
                }
                
                try {
                    JsonNode json = objectMapper.readTree(text);
                    String cmd = json.path("cmd").asText("");
                    String reqId = json.path("headers").path("req_id").asText("");
                    
                    // 检查认证响应（req_id 以 "aibot_subscribe" 开头）
                    if (reqId.startsWith("aibot_subscribe")) {
                        int errcode = json.path("errcode").asInt(-1);
                        if (errcode == 0) {
                            if (log.isDebugEnabled()) {
                                log.debug("WeCom WebSocket authentication successful!");
                            }
                            wsConnected = true;
                            reconnectAttempts = 0;
                        } else {
                            String errmsg = json.path("errmsg").asText("Unknown error");
                            log.error("WeCom WebSocket authentication failed: errcode={}, errmsg={}",
                                errcode, errmsg);
                        }
                    } else if ("aibot_msg_callback".equals(cmd) || "aibot_event_callback".equals(cmd)) {
                        handleWsMessage(text);
                    } else {
                        if (log.isDebugEnabled()) {
                            log.debug("Unhandled WebSocket frame: cmd={}, reqId={}", cmd, reqId);
                        }
                    }
                } catch (Exception e) {
                    log.error("Failed to process WebSocket message: error={}", e.getMessage());
                }
            }

            @Override
            public void onClosing(WebSocket webSocket, int code, String reason) {
                log.info("WeCom WebSocket closing: code={}, reason={}", String.valueOf(code), reason);
                wsConnected = false;
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                log.info("WeCom WebSocket closed: code={}, reason={}", String.valueOf(code), reason);
                wsConnected = false;
                scheduleReconnect();
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                String responseInfo = response != null ? "code=" + response.code() + ", message=" + response.message() : "null";
                //不影响收发消息
                if (log.isDebugEnabled()) {
                    log.debug("WeCom WebSocket failure: error={}, response={}",
                        t.getMessage(), responseInfo);
                }
                wsConnected = false;
                scheduleReconnect();
            }
        });
    }

    private void scheduleReconnect() {
        if (!isRunning()) {
            return;
        }

        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            log.error("Max reconnect attempts reached, giving up");
            return;
        }

        long delay = Math.min(INITIAL_RECONNECT_DELAY_MS * (1L << reconnectAttempts), MAX_RECONNECT_DELAY_MS);
        reconnectAttempts++;

        log.info("Scheduling reconnect attempt {} in {}ms", reconnectAttempts, delay);

        Thread reconnectThread = new Thread(() -> {
            try {
                Thread.sleep(delay);
                if (isRunning() && !wsConnected) {
                    connectWebSocketDirectly();
                }
            } catch (Exception e) {
                log.error("Reconnect failed: error={}", e.getMessage());
            }
        }, "WeComReconnectThread");
        reconnectThread.setDaemon(true);
        reconnectThread.start();
    }

    private void handleWsMessage(String text) {
        try {
            JsonNode json = objectMapper.readTree(text);
            String cmd = json.path("cmd").asText("");

            if ("aibot_msg_callback".equals(cmd)) {
                handleAibotMsgCallback(json);
            } else if ("aibot_event_callback".equals(cmd)) {
                handleAibotEventCallback(json);
            } else {
                if (log.isDebugEnabled()) {
                    log.debug("Unhandled WS frame type: cmd={}, fullText={}", cmd, text);
                }
            }
        } catch (Exception e) {
            log.error("Error handling WS message: error={}", e.getMessage());
        }
    }

    private void handleAibotMsgCallback(JsonNode json) {
        log.info("[DEBUG] Full message received: json={}", json.toString());

        JsonNode body = json.path("body");
        log.info("[DEBUG] Body: body={}", body.toString());

        String chatId = body.path("chatid").asText("");
        String fromUser = body.path("from").path("userid").asText("");
        String openimId = body.path("from").path("openimId").asText("");
        String reqId = json.path("headers").path("req_id").asText("");
        String responseUrl = body.path("response_url").asText("");
        String chatType = body.path("chattype").asText("");

        log.info("[DEBUG] Parsed fields: chatId={}, fromUser={}, openimId={}, responseUrl={}, chatType={}, reqId={}",
            chatId, fromUser, openimId, responseUrl, chatType, reqId);

        if (chatId.isEmpty() && !openimId.isEmpty()) {
            chatId = openimId;
            log.info("[DEBUG] Using openimId as chatId: chatId={}", chatId);
        }

        if (chatId.isEmpty() && !fromUser.isEmpty()) {
            chatId = fromUser;
        }

        if ("single".equals(chatType) && !responseUrl.isEmpty() && !fromUser.isEmpty()) {
            String cleanedUrl = responseUrl.trim().replaceAll("^`|`$", "");
            sessionResponseUrls.put(fromUser, cleanedUrl);
            log.info("[DEBUG] Stored response_url for P2P chat: fromUser={}, responseUrlPreview={}",
                fromUser, cleanedUrl.substring(0, Math.min(50, cleanedUrl.length())) + "...");
        }

        String content = null;

        JsonNode textNode = body.path("text");
        log.info("[DEBUG] Text node: textNode={}", textNode.toString());

        if (textNode.has("content")) {
            content = textNode.path("content").asText("");
        }

        log.info("[DEBUG] Content: content={}", content);

        if (content != null && !content.isEmpty()) {
            handleBotMessage(fromUser, chatId, content, reqId);
        } else {
            log.warn("[DEBUG] No content found in message");
        }
    }

    private void handleAibotEventCallback(JsonNode json) {
        JsonNode body = json.path("body");
        String chatId = body.path("chatid").asText("");
        String fromUser = body.path("from").path("userid").asText("");
        String openimId = body.path("from").path("openimId").asText("");
        String eventType = body.path("event").path("eventtype").asText("");

        if (chatId.isEmpty() && !openimId.isEmpty()) {
            chatId = openimId;
        }

        if ("enter_chat".equals(eventType)) {
            handleEnterChat(fromUser, chatId);
        }
    }

    private void handleBotMessage(String fromUser, String chatId, String content, String reqId) {
        log.info("[DEBUG] handleBotMessage called: fromUser={}, chatId={}, content={}, reqId={}",
            fromUser, chatId, content, reqId);

        if (content.isEmpty() || fromUser.isEmpty()) {
            log.warn("[DEBUG] Missing fromUser or content");
            return;
        }

        if (chatId.isEmpty()) {
            chatId = fromUser;
        }

        log.info("Received WeCom Bot message: from_user={}, chat_id={}, preview={}",
            fromUser, chatId, StringUtils.truncate(content, 50));

        Map<String, String> metadata = new HashMap<>();
        metadata.put("platform", "wecom");
        metadata.put("mode", "bot");
        metadata.put("req_id", reqId);

        handleMessage(fromUser, chatId, content, null, metadata);
    }

    private void handleEnterChat(String fromUser, String chatId) {
        if (fromUser.isEmpty() && chatId.isEmpty()) {
            return;
        }

        if (chatId.isEmpty()) {
            chatId = fromUser;
        }

        log.info("User entered chat: from_user={}, chat_id={}", fromUser, chatId);
    }

    @Override
    public void stop() {
        log.info("Stopping WeCom channel...");
        setRunning(false);
        wsConnected = false;

        if (webSocket != null) {
            webSocket.close(1000, "Channel stopped");
            webSocket = null;
        }

        sessionResponseUrls.clear();
        log.info("WeCom channel stopped");
    }

    @Override
    public void send(OutboundMessage message) {
        if (!isRunning()) {
            throw new IllegalStateException("WeCom channel is not running");
        }

        String chatId = message.getChatId();
        if (chatId == null || chatId.isEmpty()) {
            throw new IllegalArgumentException("Chat ID is empty");
        }

        String responseUrl = sessionResponseUrls.get(chatId);

        log.info("Sending WeCom message: chatId={}, wsConnected={}, hasBotId={}, hasResponseUrl={}",
            chatId, wsConnected,
            config.getBotId() != null && !config.getBotId().isEmpty(),
            responseUrl != null && !responseUrl.isEmpty());

        if (responseUrl != null && !responseUrl.isEmpty()) {
            sendViaResponseUrl(responseUrl, chatId, message.getContent());
        } else if (wsConnected && config.getBotId() != null && !config.getBotId().isEmpty()) {
            sendViaBotWebSocket(chatId, message.getContent());
        } else {
            throw new ChannelException("WebSocket not connected or not in Bot mode");
        }
    }

    private void sendViaResponseUrl(String responseUrl, String toUser, String content) {
        String cleanedUrl = responseUrl.trim().replaceAll("^`|`$", "");
        log.info("[DEBUG] sendViaResponseUrl called: toUser={}, responseUrlPreview={}, cleanedUrl={}",
            toUser,
            responseUrl.length() > 80 ? responseUrl.substring(0, 80) + "..." : responseUrl,
            cleanedUrl.replace("response_code=", "response_code=***"));

        try {
            ObjectNode responseBody = objectMapper.createObjectNode();
            responseBody.put("msgtype", "markdown");
            ObjectNode markdown = responseBody.putObject("markdown");
            markdown.put("content", content);

            String jsonBody = objectMapper.writeValueAsString(responseBody);
            log.info("[DEBUG] Sending response via HTTP POST: body={}", jsonBody);

            RequestBody body = RequestBody.create(jsonBody, JSON_MEDIA_TYPE);
            Request request = new Request.Builder()
                .url(cleanedUrl)
                .post(body)
                .build();

            try (Response response = httpClient.newCall(request).execute()) {
                String responseString = response.body() != null ? response.body().string() : "";
                log.info("[DEBUG] Response from response_url: code={}, body={}",
                    response.code(), responseString);

                if (!response.isSuccessful()) {
                    throw new ChannelException("Failed to send message via response_url: HTTP " + response.code());
                }
            }

            log.info("Bot message sent successfully via response_url");
        } catch (ChannelException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to send Bot message via response_url: error={}", e.getMessage());
            throw new ChannelException("Failed to send Bot message via response_url: " + e.getMessage(), e);
        }
    }

    private void sendViaBotWebSocket(String toUser, String content) {
        log.info("[DEBUG] sendViaBotWebSocket called: toUser={}, content={}",
            toUser, content);
        
        log.info("Sending Bot message via WebSocket: toUser={}, contentPreview={}",
            toUser, StringUtils.truncate(content, 50));

        if (webSocket == null) {
            log.error("[DEBUG] WebSocket is null");
            throw new ChannelException("WebSocket is null, cannot send message");
        }

        if (!wsConnected) {
            log.error("[DEBUG] WebSocket not connected");
            throw new ChannelException("WebSocket is not connected, cannot send message");
        }

        try {
            ObjectNode requestFrame = objectMapper.createObjectNode();
            requestFrame.put("cmd", "aibot_respond_msg");
            ObjectNode headers = requestFrame.putObject("headers");
            headers.put("req_id", generateReqId("aibot_respond_msg"));
            
            ObjectNode body = requestFrame.putObject("body");
            body.put("chatid", toUser);
            body.put("msgtype", "text");
            ObjectNode text = body.putObject("text");
            text.put("content", content);

            String jsonMessage = objectMapper.writeValueAsString(requestFrame);
            log.info("[DEBUG] Sending WebSocket message: message={}", jsonMessage);

            boolean sent = webSocket.send(jsonMessage);
            log.info("[DEBUG] WebSocket send result: success={}", sent);
            
            if (!sent) {
                throw new ChannelException("Failed to send WebSocket message (send returned false)");
            }

            log.info("Bot message sent successfully via WebSocket: toUser={}", toUser);
        } catch (Exception e) {
            log.error("Failed to send Bot message via WebSocket: error={}", e.getMessage());
            e.printStackTrace();
            throw new ChannelException("Failed to send Bot message via WebSocket: " + e.getMessage(), e);
        }
    }
    
    private String generateReqId(String prefix) {
        return (prefix != null ? prefix : "req") + "_" + System.currentTimeMillis() + "_" + (int)(Math.random() * 10000);
    }

    @Override
    public boolean supportsStreaming() {
        return wsConnected && config.getBotId() != null && !config.getBotId().isEmpty();
    }

    @Override
    public cn.seifly.jharness.plugin.framework.llm.LlmService.StreamCallback createStreamingCallback(String chatId) {
        if (!supportsStreaming()) {
            return null;
        }
        return null;
    }
}
