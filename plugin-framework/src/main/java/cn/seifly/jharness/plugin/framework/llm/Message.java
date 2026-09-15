package cn.seifly.jharness.plugin.framework.llm;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * LLM 消息表示，支持多模态内容（文本+图片）。
 *
 * <p>由 models-plugin 迁移至 SDK，作为跨插件共享数据结构。
 */
@Data
@NoArgsConstructor
public class Message {

    private String role;
    private String content;
    private List<String> images;  // 图片路径列表，支持多模态
    private List<ToolCall> toolCalls;
    private String toolCallId;

    public Message(String role, String content) {
        this.role = role;
        this.content = content;
    }

    /**
     * 检查消息是否包含图片
     */
    public boolean hasImages() {
        return images != null && !images.isEmpty();
    }

    // Builder 方法
    public static Message system(String content) {
        return new Message("system", content);
    }

    public static Message user(String content) {
        return new Message("user", content);
    }

    /**
     * 创建带图片的用户消息
     */
    public static Message user(String content, List<String> images) {
        Message msg = new Message("user", content);
        msg.setImages(images);
        return msg;
    }

    public static Message assistant(String content) {
        return new Message("assistant", content);
    }

    public static Message tool(String toolCallId, String content) {
        Message msg = new Message("tool", content);
        msg.setToolCallId(toolCallId);
        return msg;
    }
}
