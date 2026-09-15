package cn.seifly.jharness.plugins.channel;

import cn.seifly.jharness.plugin.framework.bus.MessageBus;
import cn.seifly.jharness.plugin.framework.config.ChannelsConfig;
import cn.seifly.jharness.plugin.framework.redis.RedisKeys;
import cn.seifly.jharness.plugin.framework.redis.RedisShared;
import cn.seifly.jharness.plugin.framework.service.ChannelService;
import cn.seifly.jharness.plugin.framework.service.ServiceRegistration;
import cn.seifly.jharness.plugin.framework.service.ServiceRegistry;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.pf4j.Plugin;
import org.pf4j.PluginWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

/**
 * channel-plugin 生命周期类。
 *
 * <p>启动时从 Redis 加载通道配置，创建 {@link ChannelManager} 并启动所有已配置通道，
 * 然后通过 {@link ServiceRegistry} 注册 {@link ChannelService}，
 * 供 ui-plugin 的 {@code ChannelsController} 获取微信登录状态、通道状态等。
 */
@Slf4j
public class ChannelPlugin extends Plugin {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Autowired
    private ServiceRegistry services;

    private volatile ChannelManager channelManager;
    private volatile MessageBus messageBus;
    private volatile ServiceRegistration<ChannelService> registration;
    private volatile ServiceRegistration<MessageBus> busRegistration;
    private volatile WebhookServer webhookServer;

    public ChannelPlugin(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public void start() {
        // 1. 从 Redis 加载通道配置
        ChannelsConfig channelsConfig = loadChannelsConfig();

        // 2. 创建 MessageBus（SDK 共享，用于通道与 workflow-plugin 之间路由消息）
        this.messageBus = new MessageBus();

        // 3. 创建 ChannelManager 并启动所有已配置通道
        this.channelManager = new ChannelManager(channelsConfig, messageBus);
        channelManager.startAll();

        // 3.1 启动 Webhook Server（接收 QQ / 企业微信 / 钉钉 / 飞书平台回调）
        ChannelsConfig.WebhookConfig webhookConfig = channelsConfig.getWebhook();
        if (webhookConfig != null && webhookConfig.isEnabled()) {
            String host = webhookConfig.getHost() != null ? webhookConfig.getHost() : "0.0.0.0";
            int port = webhookConfig.getPort() > 0 ? webhookConfig.getPort() : 18790;
            this.webhookServer = new WebhookServer(host, port, channelManager, channelsConfig);
            try {
                webhookServer.start();
            } catch (Exception e) {
                log.error("Webhook Server 启动失败: host={}, port={}, error={}",
                        host, port, e.getMessage());
                this.webhookServer = null;
            }
        } else {
            log.info("Webhook Server 未启用（channelsConfig.webhook.enabled=false）");
        }

        // 4. 注册 ChannelService 到 ServiceRegistry
        ChannelService impl = new ChannelServiceImpl(channelManager);
        this.registration = services.register(ChannelService.class, impl, "channel");

        // 5. 注册 MessageBus 到 ServiceRegistry：workflow-plugin 消费方通过 services.get(MessageBus.class)
        //    取同一实例启动入站消费者循环，使通道消息能进入 MessageRouter 处理
        this.busRegistration = services.register(MessageBus.class, messageBus, "channel");

        log.info("ChannelPlugin 启动完成: channels={}, channel_service_registered=true, bus_registered=true",
                channelManager.getEnabledChannels());
    }

    @Override
    public void stop() {
        // 1. 注销 ChannelService
        ServiceRegistration<ChannelService> reg = this.registration;
        if (reg != null) {
            reg.unregister();
            this.registration = null;
        }

        // 2. 注销 MessageBus 服务（先于关闭 bus，避免消费方在 close 后仍持有引用）
        ServiceRegistration<MessageBus> busReg = this.busRegistration;
        if (busReg != null) {
            busReg.unregister();
            this.busRegistration = null;
        }

        // 2.1 停止 Webhook Server
        WebhookServer ws = this.webhookServer;
        if (ws != null) {
            try {
                ws.stop();
            } catch (Exception e) {
                log.warn("停止 Webhook Server 出错: error={}", e.getMessage());
            }
            this.webhookServer = null;
        }

        // 3. 停止所有通道
        ChannelManager cm = this.channelManager;
        if (cm != null) {
            cm.stopAll();
            this.channelManager = null;
        }

        // 4. 关闭 MessageBus
        MessageBus bus = this.messageBus;
        if (bus != null) {
            bus.close();
            this.messageBus = null;
        }

        log.info("ChannelPlugin 已停止，资源已释放");
    }

    /**
     * 从 Redis 加载通道配置。
     *
     * <p>Redis 中存储格式为 {@code Map<channelName, Map<字段...>>}，
     * 使用 Jackson convertValue 转换为 {@link ChannelsConfig}。
     */
    @SuppressWarnings("unchecked")
    private ChannelsConfig loadChannelsConfig() {
        try {
            Map<String, Map<String, Object>> raw = RedisShared.store()
                    .getJSON(RedisKeys.NOTIFICATIONS_CHANNELS, Map.class);
            if (raw == null || raw.isEmpty()) {
                log.info("Redis 中无通道配置，使用默认配置");
                return new ChannelsConfig();
            }
            // 将整个 Map 序列化为 JSON 再反序列化为 ChannelsConfig
            String json = MAPPER.writeValueAsString(raw);
            ChannelsConfig config = MAPPER.readValue(json, ChannelsConfig.class);
            // 补充微信通道的 resumeContextJson（单独存储）
            String wechatConfigJson = RedisShared.store()
                    .getJSON(RedisKeys.NOTIFICATIONS_WECHAT_CONFIG, String.class);
            if (wechatConfigJson != null && !wechatConfigJson.isEmpty()) {
                ChannelsConfig.WechatConfig wc = config.getWechat();
                if (wc != null) {
                    // wechatConfigJson 可能是一个 JSON 对象字符串
                    try {
                        Map<String, Object> wcMap = MAPPER.readValue(wechatConfigJson, Map.class);
                        Object resumeCtx = wcMap.get("resumeContextJson");
                        if (resumeCtx instanceof String s && !s.isEmpty()) {
                            wc.setResumeContextJson(s);
                        }
                    } catch (Exception e) {
                        // wechatConfigJson 本身就是 resumeContextJson 字符串
                        wc.setResumeContextJson(wechatConfigJson);
                    }
                }
            }
            return config;
        } catch (Exception e) {
            log.warn("加载通道配置失败，使用默认配置: error={}", e.getMessage());
            return new ChannelsConfig();
        }
    }
}
