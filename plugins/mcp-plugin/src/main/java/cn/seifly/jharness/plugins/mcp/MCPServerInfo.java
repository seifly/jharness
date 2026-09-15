package cn.seifly.jharness.plugins.mcp;

import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 服务器信息
 *
 * 存储 MCP Server 的元信息和能力
 */
@Data
public class MCPServerInfo {

    private String name;
    private String version;
    private String protocolVersion;
    private Map<String, Object> capabilities;
    private List<ToolInfo> tools;
    private List<ResourceInfo> resources;
    private List<PromptInfo> prompts;

    public MCPServerInfo() {
        this.capabilities = new HashMap<>();
        this.tools = new ArrayList<>();
        this.resources = new ArrayList<>();
        this.prompts = new ArrayList<>();
    }

    /**
     * 工具信息
     */
    @Data
    public static class ToolInfo {
        private String name;
        private String description;
        private Map<String, Object> inputSchema;

        public ToolInfo() {
        }

        public ToolInfo(String name, String description, Map<String, Object> inputSchema) {
            this.name = name;
            this.description = description;
            this.inputSchema = inputSchema;
        }
    }

    /**
     * 资源信息
     */
    @Data
    public static class ResourceInfo {
        private String uri;
        private String name;
        private String description;
        private String mimeType;
    }

    /**
     * 提示词模板信息
     */
    @Data
    public static class PromptInfo {
        private String name;
        private String description;
        private List<ArgumentInfo> arguments;

        public PromptInfo() {
            this.arguments = new ArrayList<>();
        }
    }

    /**
     * 参数信息
     */
    @Data
    public static class ArgumentInfo {
        private String name;
        private String description;
        private boolean required;
    }
}
