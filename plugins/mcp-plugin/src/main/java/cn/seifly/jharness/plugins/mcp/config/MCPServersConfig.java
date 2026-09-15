package cn.seifly.jharness.plugins.mcp.config;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * MCP 服务器配置
 *
 * 管理多个 MCP 服务器的连接配置
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

    /**
     * 单个 MCP 服务器配置
     */
    @Getter
    @Setter
    public static class MCPServerConfig {
        /** 服务器名称（唯一标识） */
        private String name;
        /** 服务器描述 */
        private String description;
        /**
         * 传输类型：sse、streamable-http 或 stdio，默认 sse。
         * - sse：通过 HTTP SSE 长连接，需要配置 endpoint
         * - streamable-http：通过 HTTP POST 直接发送请求，需要配置 endpoint（MCP 2025-03-26）
         * - stdio：通过子进程 stdin/stdout 通信，需要配置 command
         */
        private String type;
        /** SSE 模式的服务器端点 URL */
        private String endpoint;
        /** SSE 模式的 API Key（可选） */
        private String apiKey;
        /** Stdio 模式的可执行命令（如 "npx", "python3", "node"） */
        private String command;
        /** Stdio 模式的命令参数列表 */
        private List<String> args;
        /** Stdio 模式的额外环境变量 */
        private Map<String, String> env;
        /** 是否启用 */
        private boolean enabled;
        /** 请求超时时间（毫秒） */
        private int timeout;

        public MCPServerConfig() {
            this.type = "sse";
            this.enabled = true;
            this.timeout = 30000; // 默认 30 秒
        }

        /**
         * 判断是否为 stdio 传输类型
         */
        public boolean isStdio() {
            return "stdio".equalsIgnoreCase(type);
        }

        /**
         * 判断是否为 streamable-http 传输类型
         */
        public boolean isStreamableHttp() {
            return "streamable-http".equalsIgnoreCase(type);
        }
    }
}
