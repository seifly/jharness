package cn.seifly.jharness.plugins.channel;

import cn.seifly.jharness.plugin.framework.service.ChannelService;
import cn.seifly.jharness.plugins.channel.channels.WechatChannel;

import java.util.List;
import java.util.Map;

/**
 * {@link ChannelService} 的 channel-plugin 实现。
 *
 * <p>包装运行期 {@link ChannelManager}，通过 {@code ServiceRegistry} 暴露给
 * ui-plugin 等消费方，避免跨插件直接引用实现类。
 */
public class ChannelServiceImpl implements ChannelService {

    private final ChannelManager channelManager;

    public ChannelServiceImpl(ChannelManager channelManager) {
        this.channelManager = channelManager;
    }

    @Override
    public Map<String, Object> getChannelStatus() {
        return channelManager.getStatus();
    }

    @Override
    public boolean startChannel(String name) {
        return channelManager.startChannel(name);
    }

    @Override
    public boolean stopChannel(String name) {
        return channelManager.stopChannel(name);
    }

    @Override
    public Map<String, Object> getWechatLoginStatus() {
        return channelManager.getChannel("wechat")
                .filter(c -> c instanceof WechatChannel)
                .map(c -> ((WechatChannel) c).getLoginStatus())
                .orElseGet(() -> {
                    Map<String, Object> status = new java.util.HashMap<>();
                    status.put("enabled", false);
                    status.put("running", false);
                    status.put("loggedIn", false);
                    status.put("state", "not_started");
                    status.put("error", "Wechat channel not initialized");
                    return status;
                });
    }

    @Override
    public Map<String, Object> ensureWechatChannel() {
        // 按需启动微信通道，返回登录状态（含二维码）
        channelManager.ensureWechatChannel(channelManager.getChannelsConfig().getWechat());
        return getWechatLoginStatus();
    }

    @Override
    public List<String> getEnabledChannelNames() {
        return channelManager.getEnabledChannels();
    }

    @Override
    public boolean handleIncomingMessage(String channelName, String messageJson) {
        Channel channel = channelManager.getChannel(channelName).orElse(null);
        if (channel == null || !channel.isRunning()) {
            return false;
        }
        channel.handleIncomingMessage(messageJson);
        return true;
    }
}
