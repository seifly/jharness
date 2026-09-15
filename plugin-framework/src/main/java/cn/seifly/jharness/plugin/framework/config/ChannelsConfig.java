package cn.seifly.jharness.plugin.framework.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 消息通道配置类（SDK 共享）。
 * 支持多个消息平台：Telegram、Discord、WhatsApp、飞书、钉钉、QQ、MaixCam、WeCom、Wechat。
 *
 * <p>此类作为跨插件共享数据结构提升至 plugin-framework SDK，
 * 避免 PF4J 独立类加载器导致 workflow-plugin 在运行期抛出
 * {@code NoClassDefFoundError}。
 */
@Getter
@Setter
public class ChannelsConfig {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TelegramConfig telegram;
    private DiscordConfig discord;
    private WhatsAppConfig whatsapp;
    private WechatConfig wechat;
    private FeishuConfig feishu;
    private DingTalkConfig dingtalk;
    private QQConfig qq;
    private MaixCamConfig maixcam;
    private WeComConfig wecom;
    private WebhookConfig webhook;

    public ChannelsConfig() {
        this.telegram = new TelegramConfig();
        this.discord = new DiscordConfig();
        this.whatsapp = new WhatsAppConfig();
        this.wechat = new WechatConfig();
        this.feishu = new FeishuConfig();
        this.dingtalk = new DingTalkConfig();
        this.qq = new QQConfig();
        this.maixcam = new MaixCamConfig();
        this.wecom = new WeComConfig();
        this.webhook = new WebhookConfig();
    }

    // ==================== Inner channel configs ====================

    @Getter
    @Setter
    public static class TelegramConfig {
        private boolean enabled;
        private String token;
        private List<String> allowFrom;

        public TelegramConfig() {
            this.enabled = false;
            this.allowFrom = new ArrayList<>();
        }
    }

    @Getter
    @Setter
    public static class DiscordConfig {
        private boolean enabled;
        private String token;
        private List<String> allowFrom;

        public DiscordConfig() {
            this.enabled = false;
            this.allowFrom = new ArrayList<>();
        }
    }

    @Getter
    @Setter
    public static class WhatsAppConfig {
        private boolean enabled;
        private String bridgeUrl;
        private List<String> allowFrom;

        public WhatsAppConfig() {
            this.enabled = false;
            this.bridgeUrl = "ws://localhost:3001";
            this.allowFrom = new ArrayList<>();
        }
    }

    @Getter
    @Setter
    public static class FeishuConfig {
        private boolean enabled;
        private String appId;
        private String appSecret;
        private String encryptKey;
        private String verificationToken;
        private String connectionMode;
        private List<String> allowFrom;

        public FeishuConfig() {
            this.enabled = false;
            this.connectionMode = "websocket";
            this.allowFrom = new ArrayList<>();
        }

        @JsonIgnore
        public boolean isWebSocketMode() {
            return "websocket".equalsIgnoreCase(connectionMode);
        }
    }

    @Getter
    @Setter
    public static class WechatConfig {
        private boolean enabled;
        private int pollIntervalMs;
        private int loginTimeoutSeconds;
        private String botToken;
        private String resumeContextJson;
        private List<String> allowFrom;

        public WechatConfig() {
            this.enabled = false;
            this.pollIntervalMs = 1000;
            this.loginTimeoutSeconds = 180;
            this.allowFrom = new ArrayList<>();
        }
    }

    @Getter
    @Setter
    public static class DingTalkConfig {
        private boolean enabled;
        private String clientId;
        private String clientSecret;
        private String webhook;
        private String connectionMode;
        private List<String> allowFrom;

        public DingTalkConfig() {
            this.enabled = false;
            this.connectionMode = "stream";
            this.allowFrom = new ArrayList<>();
        }

        @JsonIgnore
        public boolean isStreamMode() {
            return "stream".equalsIgnoreCase(connectionMode);
        }
    }

    @Getter
    @Setter
    public static class QQConfig {
        private boolean enabled;
        private String appId;
        private String appSecret;
        private List<String> allowFrom;

        public QQConfig() {
            this.enabled = false;
            this.allowFrom = new ArrayList<>();
        }
    }

    @Getter
    @Setter
    public static class MaixCamConfig {
        private boolean enabled;
        private String host;
        private int port;
        private List<String> allowFrom;

        public MaixCamConfig() {
            this.enabled = false;
            this.host = "0.0.0.0";
            this.port = 18790;
            this.allowFrom = new ArrayList<>();
        }
    }

    @Getter
    @Setter
    public static class WeComConfig {
        private boolean enabled;
        private String botId;
        private String secret;
        private String dmPolicy;
        private List<String> allowFrom;

        public WeComConfig() {
            this.enabled = false;
            this.dmPolicy = "open";
            this.allowFrom = new ArrayList<>();
        }
    }

    /**
     * Webhook Server 配置：接收 QQ / 企业微信 / 钉钉 / 飞书等平台的 HTTP 回调。
     */
    @Getter
    @Setter
    public static class WebhookConfig {
        private boolean enabled;
        private String host;
        private int port;

        public WebhookConfig() {
            this.enabled = true;
            this.host = "0.0.0.0";
            this.port = 8090;
        }
    }

    public Map<String, Object> get(String name) {
        if ("telegram".equals(name)) {
            return MAPPER.convertValue(telegram, new TypeReference<Map<String, Object>>() {
            });
        } else if ("discord".equals(name)) {
            return MAPPER.convertValue(discord, new TypeReference<Map<String, Object>>() {
            });
        } else if ("wechat".equals(name)) {
            return MAPPER.convertValue(wechat, new TypeReference<Map<String, Object>>() {
            });
        } else if ("feishu".equals(name)) {
            return MAPPER.convertValue(feishu, new TypeReference<Map<String, Object>>() {
            });
        } else if ("dingtalk".equals(name)) {
            return MAPPER.convertValue(dingtalk, new TypeReference<Map<String, Object>>() {
            });
        } else if ("qq".equals(name)) {
            return MAPPER.convertValue(qq, new TypeReference<Map<String, Object>>() {
            });
        } else if ("maixcam".equals(name)) {
            return MAPPER.convertValue(maixcam, new TypeReference<Map<String, Object>>() {
            });
        } else if ("wecom".equals(name)) {
            return MAPPER.convertValue(wecom, new TypeReference<Map<String, Object>>() {
            });
        } else {
            return null;
        }
    }

    public void setObject(String name, Map<String, Object> channel, ChannelsConfig channels) {
        if ("telegram".equals(name)) {
            channels.setTelegram(MAPPER.convertValue(channel, TelegramConfig.class));
        } else if ("discord".equals(name)) {
            channels.setDiscord(MAPPER.convertValue(channel, DiscordConfig.class));
        } else if ("wechat".equals(name)) {
            channels.setWechat(MAPPER.convertValue(channel, WechatConfig.class));
        } else if ("feishu".equals(name)) {
            channels.setFeishu(MAPPER.convertValue(channel, FeishuConfig.class));
        } else if ("dingtalk".equals(name)) {
            channels.setDingtalk(MAPPER.convertValue(channel, DingTalkConfig.class));
        } else if ("qq".equals(name)) {
            channels.setQq(MAPPER.convertValue(channel, QQConfig.class));
        } else if ("maixcam".equals(name)) {
            channels.setMaixcam(MAPPER.convertValue(channel, MaixCamConfig.class));
        } else if ("wecom".equals(name)) {
            channels.setWecom(MAPPER.convertValue(channel, WeComConfig.class));
        }
    }
}
