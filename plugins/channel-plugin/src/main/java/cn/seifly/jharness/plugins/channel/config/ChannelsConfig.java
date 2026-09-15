package cn.seifly.jharness.plugins.channel.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * 消息通道配置类
 * 支持多个消息平台：Telegram、Discord、WhatsApp、飞书、钉钉、QQ、MaixCam
 */
@Getter
@Setter
public class ChannelsConfig {

    private TelegramConfig telegram;
    private DiscordConfig discord;
    private WhatsAppConfig whatsapp;
    private WechatConfig wechat;
    private FeishuConfig feishu;
    private DingTalkConfig dingtalk;
    private QQConfig qq;
    private MaixCamConfig maixcam;
    private WeComConfig wecom;

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
    }

    // 内部配置类

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
            this.botToken = null;
            this.resumeContextJson = null;
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
}
