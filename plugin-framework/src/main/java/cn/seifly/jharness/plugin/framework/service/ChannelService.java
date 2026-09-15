package cn.seifly.jharness.plugin.framework.service;

import java.util.List;
import java.util.Map;

/**
 * 通道管理服务契约。
 *
 * <p>由 channel-plugin 实现，通过 {@link ServiceRegistry} 注册，
 * 供 ui-plugin 的 {@code ChannelsController} 等消费方获取。
 *
 * <p>跨插件 ClassLoader 安全：消费方仅依赖此 SDK 接口，
 * 不直接引用 channel-plugin 内部的 {@code ChannelManager} / {@code WechatChannel}。
 */
public interface ChannelService extends Service {

    /**
     * 获取所有已注册通道的状态。
     *
     * @return Map：key=通道名，value=状态信息（含 enabled、running）
     */
    Map<String, Object> getChannelStatus();

    /**
     * 启动指定通道。
     *
     * @param name 通道名
     * @return true 表示启动成功
     */
    boolean startChannel(String name);

    /**
     * 停止指定通道。
     *
     * @param name 通道名
     * @return true 表示停止成功
     */
    boolean stopChannel(String name);

    /**
     * 获取微信扫码登录状态（含二维码图片 base64）。
     *
     * @return 状态 Map，字段与 {@code WechatChannel.getLoginStatus()} 一致：
     *         enabled、running、loggedIn、state、error、botId、qrCodeContent、qrCodeImage
     */
    Map<String, Object> getWechatLoginStatus();

    /**
     * 按需启动微信通道（若已启动且运行中则直接返回）。
     *
     * @return 启动后的微信登录状态 Map（同 {@link #getWechatLoginStatus()}）
     */
    Map<String, Object> ensureWechatChannel();

    /**
     * 获取已启用的通道名称列表。
     *
     * @return 通道名称列表
     */
    List<String> getEnabledChannelNames();

    /**
     * 将外部网关（如 qq-gateway）转发过来的消息交给指定通道处理。
     *
     * <p>实现侧在 channel-plugin 内部完成类型转换（转为具体通道并调用其
     * {@code handleIncomingMessage}），从而保持 SDK 边界。
     *
     * @param channelName 通道名（如 "qq"）
     * @param messageJson 消息 JSON 字符串
     * @return true 表示通道已接收并处理；false 表示通道不存在或未运行
     */
    boolean handleIncomingMessage(String channelName, String messageJson);
}
