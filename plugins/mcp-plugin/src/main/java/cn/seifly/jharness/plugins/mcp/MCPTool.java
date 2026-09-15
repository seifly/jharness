package cn.seifly.jharness.plugins.mcp;

import cn.seifly.jharness.plugin.framework.tools.Tool;
import cn.seifly.jharness.plugin.framework.tools.ToolException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 直接工具 — 将 MCP 服务器的每个工具直接注册为独立的 Tool。
 *
 * 工具名格式：mcp_{serverName}_{toolName}
 * 例如：mcp_my-mcp-server_search_documents
 */
@Slf4j
public class MCPTool implements Tool {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final String serverName;
    private final String toolName;
    private final String registeredName;
    private final MCPServerInfo.ToolInfo toolInfo;
    private volatile MCPClient client;
    private final MCPManager manager;

    public MCPTool(String serverName, MCPServerInfo.ToolInfo toolInfo,
                   MCPClient client, MCPManager manager) {
        this.serverName = serverName;
        this.toolName = toolInfo.getName();
        this.registeredName = "mcp_" + serverName + "_" + toolInfo.getName();
        this.toolInfo = toolInfo;
        this.client = client;
        this.manager = manager;
    }

    @Override
    public String name() {
        return registeredName;
    }

    @Override
    public String description() {
        String desc = toolInfo.getDescription();
        return desc != null && !desc.isEmpty()
                ? desc
                : "MCP tool: " + toolName + " (server: " + serverName + ")";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> inputSchema = toolInfo.getInputSchema();
        if (inputSchema != null && !inputSchema.isEmpty()) {
            return inputSchema;
        }
        Map<String, Object> emptySchema = new HashMap<>();
        emptySchema.put("type", "object");
        emptySchema.put("properties", new HashMap<>());
        return emptySchema;
    }

    @Override
    public String execute(Map<String, Object> args) throws ToolException {
        // 检查连接状态，如果断开则尝试自动重连
        if (!client.isConnected()) {
            try {
                log.info("MCP server disconnected, attempting reconnect: server={}, tool={}",
                        serverName, toolName);
                MCPClient newClient = manager.reconnect(serverName);
                this.client = newClient;
                log.info("MCP server reconnected successfully: server={}", serverName);
            } catch (Exception reconnectError) {
                log.error("MCP server reconnect failed: server={}, error={}",
                        serverName, reconnectError.getMessage());
                return "Error: MCP server '" + serverName + "' disconnected and reconnect failed: "
                        + reconnectError.getMessage();
            }
        }

        // 构造 MCP tools/call 请求参数
        Map<String, Object> mcpParams = new HashMap<>();
        mcpParams.put("name", toolName);
        if (args != null && !args.isEmpty()) {
            mcpParams.put("arguments", args);
        }

        try {
            log.info("Calling MCP tool: server={}, tool={}", serverName, toolName);

            MCPMessage response = client.sendRequest("tools/call", mcpParams);

            if (response.getResult() != null) {
                return formatResult(response.getResult());
            } else {
                return "MCP tool call succeeded but returned no result";
            }

        } catch (MCPClient.MCPException e) {
            log.error("MCP tool call failed: server={}, tool={}, error={}",
                    serverName, toolName, e.getMessage());
            return "Error: MCP tool call failed - " + e.getMessage();
        } catch (Exception e) {
            log.error("MCP tool call error: server={}, tool={}, error={}",
                    serverName, toolName, e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    /**
     * 更新底层 MCPClient 引用（重连后调用）
     */
    public void updateClient(MCPClient newClient) {
        this.client = newClient;
    }

    public String getServerName() {
        return serverName;
    }

    public String getToolName() {
        return toolName;
    }

    /**
     * 格式化 MCP 工具调用结果
     */
    @SuppressWarnings("unchecked")
    private String formatResult(Map<String, Object> result) {
        try {
            if (result.containsKey("content")) {
                Object content = result.get("content");
                if (content instanceof List) {
                    List<Map<String, Object>> contentList = (List<Map<String, Object>>) content;
                    StringBuilder resultBuilder = new StringBuilder();
                    for (Map<String, Object> item : contentList) {
                        String type = (String) item.get("type");
                        if ("text".equals(type)) {
                            resultBuilder.append(item.get("text")).append("\n");
                        } else {
                            resultBuilder.append("[").append(type).append("]\n");
                        }
                    }
                    return resultBuilder.toString().trim();
                } else {
                    return String.valueOf(content);
                }
            }
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
        } catch (Exception e) {
            log.warn("Failed to format result: error={}", e.getMessage());
            return result.toString();
        }
    }
}
