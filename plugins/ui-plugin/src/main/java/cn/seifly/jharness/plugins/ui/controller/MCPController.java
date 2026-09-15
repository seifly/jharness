package cn.seifly.jharness.plugins.ui.controller;

import cn.seifly.jharness.plugin.framework.redis.ConfigStore;
import cn.seifly.jharness.plugin.framework.redis.RedisKeys;
import cn.seifly.jharness.plugin.framework.redis.RedisShared;
import cn.seifly.jharness.plugins.ui.WebUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 服务器配置管理 API 控制器
 *
 * 支持的操作：
 * - GET  /api/mcp          获取 MCP 配置（enabled 状态 + 服务器列表）
 * - PUT  /api/mcp          更新 MCP 全局开关（enabled）
 * - POST /api/mcp          添加新的 MCP 服务器配置
 * - PUT  /api/mcp/{name}   更新指定 MCP 服务器配置
 * - DELETE /api/mcp/{name} 删除指定 MCP 服务器配置
 * - POST /api/mcp/{name}/test 测试连接并获取工具列表
 *
 * <p>迁移说明：MCP 服务器配置从 Redis 的 {@link RedisKeys#MCP_SERVERS} 读写，
 * 结构为 {@code Map<enabled, List<Map<name, type, endpoint, apiKey, command, args, env, enabled, timeout>>>}，
 * 不再注入跨插件 {@code Config}。测试连接原依赖运行期 MCPClient（mcp-plugin 运行期对象），
 * 暂以 TODO 占位响应，待调度通道实现后接入。
 */
@RestController
@RequestMapping("/api/mcp")
@CrossOrigin(origins = "${jclaw.gateway.cors-origin:*}", allowedHeaders = "*", methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE, RequestMethod.OPTIONS})
@Slf4j
public class MCPController {

    private final ConfigStore store = RedisShared.store();

    /**
     * 获取 MCP 配置（enabled 状态 + 服务器列表）
     *
     * @return MCP 配置
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getConfig() {
        Map<String, Object> mcpConfig = getMcpConfig();

        Map<String, Object> result = new HashMap<>();
        result.put("enabled", mcpConfig.getOrDefault("enabled", false));

        List<Map<String, Object>> serversArray = new ArrayList<>();
        for (Map<String, Object> server : getServers(mcpConfig)) {
            Map<String, Object> serverNode = new LinkedHashMap<>();
            serverNode.put("name", server.getOrDefault("name", ""));
            serverNode.put("type", server.getOrDefault("type", "sse"));
            serverNode.put("description", server.getOrDefault("description", ""));
            serverNode.put("endpoint", server.getOrDefault("endpoint", ""));

            Object apiKey = server.get("apiKey");
            serverNode.put("apiKey", apiKey instanceof String s ? WebUtils.maskSecret(s) : "");

            serverNode.put("command", server.getOrDefault("command", ""));
            if (server.get("args") != null) {
                serverNode.put("args", new ArrayList<>((List<?>) server.get("args")));
            }
            if (server.get("env") != null && server.get("env") instanceof Map<?, ?> envMap) {
                serverNode.put("env", new LinkedHashMap<>(castStringMap(envMap)));
            }
            serverNode.put("enabled", server.getOrDefault("enabled", true));
            serverNode.put("timeout", server.getOrDefault("timeout", 30000));
            serversArray.add(serverNode);
        }
        result.put("servers", serversArray);

        return ResponseEntity.ok(result);
    }

    /**
     * 更新 MCP 全局开关（enabled）
     *
     * @param request 包含 enabled 字段的请求体
     * @return 更新结果
     */
    @PutMapping
    public ResponseEntity<Map<String, Object>> updateEnabled(@RequestBody Map<String, Object> request) {
        Map<String, Object> mcpConfig = getMcpConfig();

        if (request.containsKey("enabled")) {
            mcpConfig.put("enabled", request.get("enabled"));
        }

        saveMcpConfig(mcpConfig);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "MCP config updated");

        return ResponseEntity.ok(result);
    }

    /**
     * 添加新的 MCP 服务器配置
     *
     * @param request 包含服务器配置的请求体
     * @return 创建结果
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> addServer(@RequestBody Map<String, Object> request) {
        String name = (String) request.getOrDefault("name", "");

        if (name == null || name.isEmpty()) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Server name is required");
            return ResponseEntity.status(400).body(error);
        }

        Map<String, Object> mcpConfig = getMcpConfig();
        List<Map<String, Object>> servers = getServers(mcpConfig);

        // 检查名称是否已存在
        boolean exists = servers.stream().anyMatch(s -> name.equals(s.get("name")));
        if (exists) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Server '" + name + "' already exists");
            return ResponseEntity.status(409).body(error);
        }

        Map<String, Object> serverConfig = new LinkedHashMap<>();
        serverConfig.put("name", name);
        serverConfig.put("type", request.getOrDefault("type", "sse"));
        serverConfig.put("description", request.getOrDefault("description", ""));
        serverConfig.put("endpoint", request.getOrDefault("endpoint", ""));
        serverConfig.put("apiKey", request.getOrDefault("apiKey", ""));
        serverConfig.put("command", request.getOrDefault("command", ""));

        if (request.containsKey("args") && request.get("args") instanceof List<?>) {
            @SuppressWarnings("unchecked")
            List<String> argsList = (List<String>) request.get("args");
            serverConfig.put("args", new ArrayList<>(argsList));
        }
        if (request.containsKey("env") && request.get("env") instanceof Map<?, ?>) {
            @SuppressWarnings("unchecked")
            Map<String, String> envMap = (Map<String, String>) request.get("env");
            serverConfig.put("env", new LinkedHashMap<>(envMap));
        }

        serverConfig.put("enabled", request.getOrDefault("enabled", true));
        serverConfig.put("timeout", request.getOrDefault("timeout", 30000));

        servers.add(serverConfig);
        mcpConfig.put("servers", servers);
        saveMcpConfig(mcpConfig);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "Server '" + name + "' added");

        return ResponseEntity.status(201).body(result);
    }

    /**
     * 更新指定 MCP 服务器配置
     *
     * @param name 服务器名称（URL 编码）
     * @param request 包含更新字段的请求体
     * @return 更新结果
     */
    @PutMapping("/{name}")
    public ResponseEntity<Map<String, Object>> updateServer(
            @PathVariable("name") String name,
            @RequestBody Map<String, Object> request) {

        try {
            String serverName = URLDecoder.decode(name, StandardCharsets.UTF_8);
            Map<String, Object> mcpConfig = getMcpConfig();
            List<Map<String, Object>> servers = getServers(mcpConfig);
            Map<String, Object> serverConfig = findServer(servers, serverName);

            if (serverConfig == null) {
                Map<String, Object> error = new HashMap<>();
                error.put("error", "Server '" + serverName + "' not found");
                return ResponseEntity.status(404).body(error);
            }

            if (request.containsKey("type")) serverConfig.put("type", request.get("type"));
            if (request.containsKey("description")) serverConfig.put("description", request.get("description"));
            if (request.containsKey("endpoint")) serverConfig.put("endpoint", request.get("endpoint"));
            if (request.containsKey("apiKey")) {
                String apiKey = (String) request.get("apiKey");
                if (!WebUtils.isSecretMasked(apiKey)) {
                    serverConfig.put("apiKey", apiKey);
                }
            }
            if (request.containsKey("command")) serverConfig.put("command", request.get("command"));
            if (request.containsKey("args") && request.get("args") instanceof List<?>) {
                @SuppressWarnings("unchecked")
                List<String> argsList = (List<String>) request.get("args");
                serverConfig.put("args", new ArrayList<>(argsList));
            }
            if (request.containsKey("env") && request.get("env") instanceof Map<?, ?>) {
                @SuppressWarnings("unchecked")
                Map<String, String> envMap = (Map<String, String>) request.get("env");
                serverConfig.put("env", new LinkedHashMap<>(envMap));
            }
            if (request.containsKey("enabled")) serverConfig.put("enabled", request.get("enabled"));
            if (request.containsKey("timeout")) serverConfig.put("timeout", request.get("timeout"));

            saveMcpConfig(mcpConfig);

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "Server '" + serverName + "' updated");

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("MCP API error: error={}", e.getMessage());
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.status(500).body(error);
        }
    }

    /**
     * 删除指定 MCP 服务器配置
     *
     * @param name 服务器名称（URL 编码）
     * @return 删除结果
     */
    @DeleteMapping("/{name}")
    public ResponseEntity<Map<String, Object>> deleteServer(@PathVariable("name") String name) {
        try {
            String serverName = URLDecoder.decode(name, StandardCharsets.UTF_8);
            Map<String, Object> mcpConfig = getMcpConfig();
            List<Map<String, Object>> servers = getServers(mcpConfig);

            boolean removed = servers.removeIf(s -> serverName.equals(s.get("name")));
            if (!removed) {
                Map<String, Object> error = new HashMap<>();
                error.put("error", "Server '" + serverName + "' not found");
                return ResponseEntity.status(404).body(error);
            }

            mcpConfig.put("servers", servers);
            saveMcpConfig(mcpConfig);

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "Server '" + serverName + "' deleted");

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("MCP API error: error={}", e.getMessage());
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.status(500).body(error);
        }
    }

    /**
     * 测试连接并获取工具列表
     *
     * <p>原实现创建临时 MCPClient 建立连接并列举工具，依赖 mcp-plugin 运行期对象，
     * 迁移后暂返回 TODO 占位响应，待调度通道实现后接入。
     *
     * @param name 服务器名称（URL 编码）
     * @return 测试结果
     */
    @PostMapping("/{name}/test")
    public ResponseEntity<Map<String, Object>> testConnection(@PathVariable("name") String name) {
        // TODO 测试连接依赖运行期 MCPClient（mcp-plugin），无法通过 Redis 共享，待调度通道实现后接入。
        String serverName = URLDecoder.decode(name, StandardCharsets.UTF_8);
        log.info("MCP test connection TODO: server={}", serverName);

        Map<String, Object> result = new HashMap<>();
        result.put("serverName", serverName);
        result.put("connected", false);
        result.put("success", false);
        result.put("error", "MCP 测试连接待调度通道实现");
        return ResponseEntity.ok(result);
    }

    // ==================== 私有辅助方法 ====================

    @SuppressWarnings("unchecked")
    private Map<String, Object> getMcpConfig() {
        Map<String, Object> mcpConfig = store.getJSON(RedisKeys.MCP_SERVERS, Map.class);
        if (mcpConfig == null) {
            mcpConfig = new LinkedHashMap<>();
        }
        if (!(mcpConfig.get("servers") instanceof List<?>)) {
            mcpConfig.put("servers", new ArrayList<>());
        }
        return mcpConfig;
    }

    private void saveMcpConfig(Map<String, Object> mcpConfig) {
        store.putJSON(RedisKeys.MCP_SERVERS, mcpConfig);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getServers(Map<String, Object> mcpConfig) {
        Object servers = mcpConfig.get("servers");
        if (servers instanceof List<?> list) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    result.add((Map<String, Object>) m);
                }
            }
            return result;
        }
        return new ArrayList<>();
    }

    private Map<String, Object> findServer(List<Map<String, Object>> servers, String name) {
        for (Map<String, Object> server : servers) {
            if (name.equals(server.get("name"))) {
                return server;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> castStringMap(Map<?, ?> envMap) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : envMap.entrySet()) {
            result.put(String.valueOf(e.getKey()), e.getValue() != null ? String.valueOf(e.getValue()) : "");
        }
        return result;
    }
}
