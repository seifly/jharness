package cn.seifly.jharness.plugin.framework.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * MCP 服务器配置（SDK 共享）。
 *
 * <p>此类作为跨插件共享数据结构提升至 plugin-framework SDK，
 * 避免 PF4J 独立类加载器导致 workflow-plugin 在运行期抛出
 * {@code NoClassDefFoundError}。
 */
@Getter
@Setter
public class MCPServersConfig {

    private boolean enabled;
    private List<MCPServerConfig> servers;

    public MCPServersConfig() {
        this.enabled = false;
        this.servers = new ArrayList<>();
    }

    /** 单个 MCP 服务器配置 */
    @Getter
    @Setter
    public static class MCPServerConfig {
        private String name;
        private String description;
        /** sse / streamable-http / stdio，默认 sse */
        private String type;
        private String endpoint;
        private String apiKey;
        private String command;
        private List<String> args;
        private Map<String, String> env;
        private boolean enabled;
        private int timeout;

        public MCPServerConfig() {
            this.type = "sse";
            this.enabled = true;
            this.timeout = 30000;
        }

        @JsonIgnore
        public boolean isStdio() { return "stdio".equalsIgnoreCase(type); }

        @JsonIgnore
        public boolean isStreamableHttp() { return "streamable-http".equalsIgnoreCase(type); }
    }
}
