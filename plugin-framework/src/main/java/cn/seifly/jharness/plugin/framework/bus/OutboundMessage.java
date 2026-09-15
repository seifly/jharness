package cn.seifly.jharness.plugin.framework.bus;

import lombok.Data;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * 发送到外部通道的出站消息。
 *
 * <p>由 SDK 共享，供 workflow-plugin（生产方）与 channel-plugin（消费方）解耦。
 */
@Data
public class OutboundMessage {

    /**
     * 消息类型枚举，用于指导各通道选择合适的发送格式
     */
    public enum MessageType {
        /** 纯文本消息 */
        TEXT,
        /** Markdown 格式消息（部分通道支持） */
        MARKDOWN,
        /** 富文本卡片消息（钉钉/飞书等支持） */
        CARD
    }

    private String channel;
    private String chatId;
    private String content;

    /**
     * 关联的会话键，便于链路追踪
     */
    private String sessionKey;

    /**
     * 消息类型，默认为纯文本
     */
    private MessageType messageType = MessageType.TEXT;

    /**
     * 消息创建时间戳，用于链路追踪和超时判断
     */
    private final Instant createdAt = Instant.now();

    /**
     * 元数据，用于传递通道特定的信息
     */
    private Map<String, String> metadata;

    public OutboundMessage() {
    }

    public OutboundMessage(String channel, String chatId, String content) {
        this.channel = channel;
        this.chatId = chatId;
        this.content = content;
    }

    public OutboundMessage(String channel, String chatId, String content, String sessionKey) {
        this.channel = channel;
        this.chatId = chatId;
        this.content = content;
        this.sessionKey = sessionKey;
    }

    public String getMetadata(String key) {
        return metadata != null ? metadata.get(key) : null;
    }

    public void putMetadata(String key, String value) {
        if (this.metadata == null) {
            this.metadata = new HashMap<>();
        }
        this.metadata.put(key, value);
    }

    @Override
    public String toString() {
        return "OutboundMessage{" +
                "channel='" + channel + '\'' +
                ", chatId='" + chatId + '\'' +
                ", sessionKey='" + sessionKey + '\'' +
                ", messageType=" + messageType +
                ", contentLength=" + (content != null ? content.length() : 0) +
                ", createdAt=" + createdAt +
                '}';
    }
}
