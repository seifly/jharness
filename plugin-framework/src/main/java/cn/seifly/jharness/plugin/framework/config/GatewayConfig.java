package cn.seifly.jharness.plugin.framework.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.Setter;

/**
 * 网关配置类
 * 配置 Webhook 服务器的主机地址、端口、认证和安全策略
 */
@Getter
@Setter
public class GatewayConfig {

    private String host;
    private int port;
    private String username;
    private String password;
    private String corsOrigin;
    private int rateLimitPerMinute;

    public GatewayConfig() {
        this.host = "0.0.0.0";
        this.port = 18790;
        this.username = "admin";
        this.password = "jclaw";
        this.corsOrigin = "*";
        this.rateLimitPerMinute = 0;
    }

    /**
     * 检查是否启用了认证。
     * username 和 password 都非空时启用认证。
     */
    @JsonIgnore
    public boolean isAuthEnabled() {
        return username != null && !username.isEmpty()
                && password != null && !password.isEmpty();
    }

    /**
     * 检查是否启用了速率限制。
     */
    @JsonIgnore
    public boolean isRateLimitEnabled() {
        return rateLimitPerMinute > 0;
    }
}
