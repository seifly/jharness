package cn.seifly.jharness.plugin.framework.tools;

/**
 * 工具上下文感知接口。
 *
 * 实现此接口的工具可以接收通道和聊天ID上下文。
 */
public interface ToolContextAware {

    void setChannelContext(String channel, String chatId);
}
