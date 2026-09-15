package cn.seifly.jharness.plugins.channel.channels;

import cn.seifly.jharness.plugins.channel.BaseChannel;
import cn.seifly.jharness.plugins.channel.ChannelException;
import cn.seifly.jharness.plugins.channel.TokenManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import cn.seifly.jharness.plugin.framework.bus.MessageBus;
import cn.seifly.jharness.plugin.framework.bus.OutboundMessage;
import cn.seifly.jharness.plugin.framework.config.ChannelsConfig;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * QQ 通道实现 - 基于腾讯 QQ 开放平台 API
 * 
 * 提供腾讯 QQ 机器人的消息处理能力：
 * - 私聊消息收发
 * - 群聊 @ 消息处理
 * - WebSocket 消息接收（需配合网关服务）
 * 
 * 核心流程：
 * 1. 使用 App ID 和 App Secret 获取访问令牌
 * 2. 通过 WebSocket 或 HTTP 接收消息事件
 * 3. 解析消息内容并发布到消息总线
 * 4. 使用 API 发送回复消息
 * 
 * 配置要求：
 * - App ID：QQ 机器人的 App ID
 * - App Secret：QQ 机器人的 App Secret
 * 
 * 注意：
 * - 需要在 QQ 开放平台注册机器人应用
 * - 消息接收需要配合网关服务使用
 */
@Slf4j(topic = "qq")
public class QQChannel extends BaseChannel {
    
    private static final String API_BASE_URL = "https://api.sgroup.qq.com";
    
    private final ChannelsConfig.QQConfig config;
    private final OkHttpClient httpClient;
    
    // 令牌管理器
    private final TokenManager tokenManager;
    
    // 已处理消息 ID（去重）
    private final Set<String> processedIds = ConcurrentHashMap.newKeySet();
    
    /**
     * 创建 QQ 通道
     * 
     * @param config QQ 配置
     * @param bus 消息总线
     */
    public QQChannel(ChannelsConfig.QQConfig config, MessageBus bus) {
        super("qq", bus, config.getAllowFrom());
        this.config = config;
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();
        
        // 初始化令牌管理器
        this.tokenManager = new TokenManager(this::fetchAccessToken);
    }
    
    @Override
    public void start() throws ChannelException {
        log.info("正在启动 QQ 通道...");
        
        // 打印配置信息用于调试
        log.info("QQ 通道配置检查: enabled={}, app_id_present={}, app_secret_present={}, allow_from_size={}",
            true,
            config.getAppId() != null && !config.getAppId().isEmpty(),
            config.getAppSecret() != null && !config.getAppSecret().isEmpty(),
            config.getAllowFrom() != null ? config.getAllowFrom().size() : 0);
        
        if (config.getAppId() == null || config.getAppId().isEmpty()) {
            log.error("QQ App ID 为空，无法启动通道");
            throw new ChannelException("QQ App ID 为空");
        }
        
        if (config.getAppSecret() == null || config.getAppSecret().isEmpty()) {
            log.error("QQ App Secret 为空，无法启动通道");
            throw new ChannelException("QQ App Secret 为空");
        }
        
        // 获取访问令牌
        try {
            log.info("正在获取 QQ 访问令牌...");
            String token = tokenManager.getValidToken();
            log.info("QQ 访问令牌获取成功: token_length={}", token.length());
        } catch (Exception e) {
            log.error("获取 QQ 访问令牌失败: error={}, error_type={}", e.getMessage(), e.getClass().getSimpleName());
            throw new ChannelException("获取访问令牌失败: " + e.getMessage(), e);
        }
        
        setRunning(true);
        log.info("QQ 通道已启动（API 模式）");
        log.info("请配合网关服务使用以接收消息");
        log.info("QQ 通道状态: running={}, channel_name={}", isRunning(), name());
    }
    
    @Override
    public void stop() {
        log.info("正在停止 QQ 通道...");
        setRunning(false);
        tokenManager.invalidate();
        processedIds.clear();
        log.info("QQ 通道已停止");
    }
    
    @Override
    public void send(OutboundMessage message) {
        if (!isRunning()) {
            log.error("QQ 通道未运行，无法发送消息: chat_id={}, content_preview={}",
                message.getChatId(),
                message.getContent() != null ? message.getContent().substring(0, Math.min(50, message.getContent().length())) : "null");
            throw new IllegalStateException("QQ 通道未运行");
        }
        
        // 获取有效令牌
        String token;
        try {
            token = tokenManager.getValidToken();
            if (log.isDebugEnabled()) {
                log.debug("QQ 令牌获取成功");
            }
        } catch (Exception e) {
            log.error("刷新 QQ 访问令牌失败: error={}", e.getMessage());
            throw new ChannelException("刷新访问令牌失败: " + e.getMessage(), e);
        }
        
        // 清理消息内容，移除不支持的字符
        // QQ 开放平台可能不支持某些 emoji 和特殊字符
        String cleanContent = cleanContentForQQ(message.getContent());
        
        log.info("准备发送 QQ 消息: chat_id={}, content_length={}, content_preview={}",
            message.getChatId(),
            cleanContent.length(),
            cleanContent.substring(0, Math.min(50, cleanContent.length())));
        
        // 构建消息体 - 参考 openclaw 的格式
        // QQ API v2 使用简单的 content 字段，不是 msg 数组
        ObjectNode body = MAPPER.createObjectNode();
        body.put("content", cleanContent);
        body.put("msg_type", 0);  // 0 = 文本消息
        
        String jsonBody;
        try {
            jsonBody = MAPPER.writeValueAsString(body);
            log.info("QQ 消息体构建成功: body_length={}, body_preview={}",
                jsonBody.length(), jsonBody);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.error("QQ 消息序列化失败: error={}", e.getMessage());
            throw new ChannelException("序列化消息失败: " + e.getMessage(), e);
        }
        
        // 发送消息 - 根据 chat_id 格式选择正确的 API 端点
        // 参考 openclaw：使用 /v2/users/{openid}/messages
        String chatId = message.getChatId();
        String url;
        
        // 检查是否是群聊消息（有 group_id 在 metadata 中）
        String groupId = message.getMetadata() != null ? message.getMetadata().get("group_id") : null;
        
        if (groupId != null && !groupId.isEmpty()) {
            // 群聊消息 - 使用 groups 端点
            url = API_BASE_URL + "/v2/groups/" + groupId + "/messages";
            log.info("检测到群聊消息，使用 groups 端点: group_id={}", groupId);
        } else {
            // 私聊消息 - 使用 users 端点（openid 格式）
            url = API_BASE_URL + "/v2/users/" + chatId + "/messages";
            log.info("发送私聊消息，使用 users 端点: chat_id={}", chatId);
        }
        
        Request request = buildJsonPostRequest(url, jsonBody, "QQBot " + token);
        
        log.info("正在发送 QQ 消息到 API: url={}, chat_id={}",
            url, message.getChatId());
        
        try {
            executeRequest(httpClient, request);
            log.info("QQ 消息发送成功: chat_id={}", message.getChatId());
        } catch (java.io.IOException e) {
            log.error("发送 QQ 消息失败: chat_id={}, error={}, error_type={}",
                message.getChatId(), e.getMessage(), e.getClass().getSimpleName());
            throw new ChannelException("发送 QQ 消息失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 清理消息内容，移除 QQ 开放平台不支持的字符
     * 
     * QQ 机器人 API 可能不支持某些特殊字符和 emoji
     */
    private String cleanContentForQQ(String content) {
        if (content == null) {
            return "";
        }
        
        // 过滤掉非基本字符（包括 emoji）
        // 保留：中文、英文、数字、基础标点、空格、换行
        StringBuilder cleaned = new StringBuilder();
        for (int i = 0; i < content.length(); i++) {
            int codePoint = content.codePointAt(i);
            
            // 跳过补充平面字符（emoji 多数在此范围）
            if (codePoint > 0xFFFF) {
                continue;
            }
            
            char c = (char) codePoint;
            
            // 检查 Unicode 区块
            Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
            
            // 保留的字符类型
            if (block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
                block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
                block == Character.UnicodeBlock.BASIC_LATIN ||
                block == Character.UnicodeBlock.GENERAL_PUNCTUATION ||
                block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS ||
                Character.isLetterOrDigit(c) ||
                Character.isWhitespace(c) ||
                c == '\n' || c == '\r' || c == '\t') {
                cleaned.append(c);
            }
            // 其他字符（如 emoji）将被跳过
        }
        
        return cleaned.toString().trim();
    }
    
    /**
     * 获取访问令牌（供 TokenManager 调用）
     */
    private TokenManager.TokenResult fetchAccessToken() throws Exception {
        String url = "https://bots.qq.com/app/getAppAccessToken";
        
        log.info("正在请求 QQ 访问令牌: url={}, app_id={}", url, config.getAppId());
        
        ObjectNode body = MAPPER.createObjectNode();
        body.put("appId", config.getAppId());
        body.put("clientSecret", config.getAppSecret());
        
        String jsonBody = MAPPER.writeValueAsString(body);
        Request request = buildJsonPostRequest(url, jsonBody);
        
        String responseBody;
        try {
            responseBody = executeRequest(httpClient, request);
            if (log.isDebugEnabled()) {
                log.debug("QQ 令牌请求响应成功: response_length={}", responseBody.length());
            }
        } catch (Exception e) {
            log.error("QQ 令牌请求失败: error={}, url={}", e.getMessage(), url);
            throw e;
        }
        
        JsonNode json = MAPPER.readTree(responseBody);
        
        String token = json.path("access_token").asText(null);
        int expiresIn = json.path("expires_in").asInt(7200);
        
        if (token == null || token.isEmpty()) {
            log.error("QQ 响应中无 access_token: response={}", responseBody);
            throw new Exception("获取 QQ 访问令牌失败: 响应中无 access_token. Response: " + responseBody);
        }
        
        log.info("QQ 访问令牌已刷新: expires_in={}, token_length={}",
            expiresIn, token.length());
        return new TokenManager.TokenResult(token, expiresIn);
    }
    
    /**
     * 处理接收到的消息（由外部网关调用）
     * 
     * @param messageJson 消息 JSON 字符串
     */
    public void handleIncomingMessage(String messageJson) {
        if (log.isDebugEnabled()) {
            log.debug("收到 QQ 原始消息: json_length={}", messageJson != null ? messageJson.length() : 0);
        }
        
        try {
            JsonNode json = MAPPER.readTree(messageJson);
            
            String messageId = json.path("id").asText(null);
            if (messageId == null) {
                log.warn("QQ 消息缺少 ID，跳过处理");
                return;
            }
            
            // 去重检查
            if (processedIds.contains(messageId)) {
                if (log.isDebugEnabled()) {
                    log.debug("QQ 消息已处理过，跳过: message_id={}", messageId);
                }
                return;
            }
            processedIds.add(messageId);
            
            // 清理过期的消息 ID
            if (processedIds.size() > 10000) {
                log.info("清理 QQ 消息 ID 缓存: old_size={}", processedIds.size());
                processedIds.clear();
            }
            
            // 提取发送者信息
            JsonNode author = json.path("author");
            String senderId = author.path("id").asText("unknown");
            if (senderId.equals("unknown")) {
                log.warn("QQ 消息缺少发送者 ID，跳过处理: message_id={}", messageId);
                return;
            }
            
            // 提取消息内容
            String content = json.path("content").asText("");
            if (content.isEmpty()) {
                if (log.isDebugEnabled()) {
                    log.debug("QQ 消息内容为空，跳过处理: message_id={}", messageId);
                }
                return;
            }
            
            // 确定 chat ID
            String chatId = senderId;
            String groupId = json.path("group_id").asText(null);
            if (groupId != null && !groupId.isEmpty()) {
                chatId = groupId;
                if (log.isDebugEnabled()) {
                    log.debug("QQ 群聊消息: group_id={}, sender_id={}", groupId, senderId);
                }
            }
            
            log.info("收到 QQ 消息: sender_id={}, chat_id={}, message_id={}, content_length={}, content_preview={}",
                senderId, chatId, messageId, content.length(), content.substring(0, Math.min(50, content.length())));
            
            // 构建元数据
            Map<String, String> metadata = new HashMap<>();
            metadata.put("message_id", messageId);
            if (groupId != null) {
                metadata.put("group_id", groupId);
            }
            
            // 通过父类统一处理权限校验和消息发布
            handleMessage(senderId, chatId, content, null, metadata);
            if (log.isDebugEnabled()) {
                log.debug("QQ 消息已发布到消息总线");
            }
            
        } catch (Exception e) {
            log.error("处理 QQ 消息时出错: error={}, error_type={}, json_preview={}",
                e.getMessage(), e.getClass().getSimpleName(),
                messageJson != null ? messageJson.substring(0, Math.min(200, messageJson.length())) : "null");
        }
    }
}
