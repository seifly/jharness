package cn.seifly.jharness.plugin.framework.bus;

import lombok.AccessLevel;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 来自外部通道的入站消息。
 *
 * <p>由 SDK 共享，供 workflow-plugin（消费方）与 channel-plugin（生产方）解耦。
 */
@Data
public class InboundMessage {

    /** 指令常量：开启新会话 */
    public static final String COMMAND_NEW_SESSION = "new_session";

    /** 指令常量：中断当前任务并退出 */
    public static final String COMMAND_STOP = "stop";

    private String channel;
    private String senderId;
    private String chatId;
    private String content;
    private List<String> media;
    private String command;
    private Map<String, String> metadata;

    /**
     * 消息到达总线的时间戳，用于链路追踪和超时判断
     */
    private final Instant receivedAt = Instant.now();

    public InboundMessage() {
    }

    public InboundMessage(String channel, String senderId, String chatId, String content) {
        this.channel = channel;
        this.senderId = senderId;
        this.chatId = chatId;
        this.content = content;
    }

    /**
     * 获取会话键，由 channel 和 chatId 动态计算得出。
     */
    public String getSessionKey() {
        if (sessionKeyOverride != null) {
            return sessionKeyOverride;
        }
        if (channel != null && chatId != null) {
            return channel + ":" + chatId;
        }
        return null;
    }

    /**
     * 覆盖自动计算的 sessionKey
     */
    public void setSessionKey(String sessionKey) {
        this.sessionKeyOverride = sessionKey;
    }

    /** 覆盖值由 getSessionKey/setSessionKey 自定义管理，禁止 Lombok 生成访问器，保持原有序列化行为 */
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private String sessionKeyOverride;

    public boolean isCommand() {
        return command != null && !command.isEmpty();
    }

    @Override
    public String toString() {
        return "InboundMessage{" +
                "channel='" + channel + '\'' +
                ", senderId='" + senderId + '\'' +
                ", chatId='" + chatId + '\'' +
                ", content='" + (content != null && content.length() > 50 ? content.substring(0, 50) + "..." : content) + '\'' +
                ", sessionKey='" + getSessionKey() + '\'' +
                ", receivedAt=" + receivedAt +
                '}';
    }
}
