package cn.seifly.jharness.plugin.framework.config;

import lombok.Getter;
import lombok.Setter;

/**
 * 社交网络配置类（Agent 间通信）
 *
 * 配置此项以启用 Agent 加入 Agent 社交网络（例如 ClawdChat.ai）
 * 并与其他 Agent 进行通信
 */
@Getter
@Setter
public class SocialNetworkConfig {

    private boolean enabled;
    private String endpoint;
    private String agentId;
    private String apiKey;
    private String agentName;
    private String agentDescription;

    public SocialNetworkConfig() {
        this.enabled = false;
        this.endpoint = "https://clawdchat.ai/api";
        this.agentId = "";
        this.apiKey = "";
        this.agentName = "jclaw";
        this.agentDescription = "A lightweight AI agent built with Java";
    }
}
