package cn.seifly.jharness.plugins.ui.controller;

import cn.seifly.jharness.plugin.framework.redis.ConfigStore;
import cn.seifly.jharness.plugin.framework.redis.RedisKeys;
import cn.seifly.jharness.plugin.framework.redis.RedisShared;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 认证相关 API 控制器
 *
 * 提供登录和认证检查端点，用于 Web 控制台的身份验证。
 *
 * <p>网关配置（authEnabled / username / password）从 Redis 的
 * {@link RedisKeys#UI_GATEWAY_CONFIG} 读取，不再注入跨插件 {@code Config}。
 */
@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = "${jclaw.gateway.cors-origin:*}", allowedHeaders = "*", methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.OPTIONS})
@Slf4j
public class AuthController {

    private final ConfigStore store = RedisShared.store();

    /**
     * 检查当前请求是否已通过认证
     *
     * 若认证未启用，直接返回 authenticated=true；否则检查请求中的 Authorization 头。
     *
     * @param authorizationHeader Authorization 请求头（可选）
     * @return 认证状态信息
     */
    @GetMapping("/check")
    public ResponseEntity<Map<String, Object>> checkAuth(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {

        Map<String, Object> gatewayConfig = gatewayConfig();
        boolean authEnabled = isAuthEnabled(gatewayConfig);

        Map<String, Object> result = new HashMap<>();

        if (!authEnabled) {
            result.put("authenticated", true);
            result.put("authEnabled", false);
            return ResponseEntity.ok(result);
        }

        if (isAuthenticated(authorizationHeader, gatewayConfig)) {
            result.put("authenticated", true);
            result.put("authEnabled", true);
            return ResponseEntity.ok(result);
        } else {
            result.put("authenticated", false);
            result.put("authEnabled", true);
            result.put("message", "Authentication required");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(result);
        }
    }

    /**
     * 处理登录请求
     *
     * 解析 username/password，匹配成功后返回 Base64 编码的 Token。
     * 认证未启用时直接返回成功。
     *
     * @param loginRequest 包含 username 和 password 的请求体
     * @return 登录结果，包含 token 或错误信息
     */
    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, String> loginRequest) {

        Map<String, Object> gatewayConfig = gatewayConfig();
        Map<String, Object> result = new HashMap<>();

        if (!isAuthEnabled(gatewayConfig)) {
            result.put("success", true);
            result.put("message", "Authentication not enabled");
            return ResponseEntity.ok(result);
        }

        String username = loginRequest.getOrDefault("username", "");
        String password = loginRequest.getOrDefault("password", "");

        if (username(gatewayConfig).equals(username) && password(gatewayConfig).equals(password)) {
            String token = Base64.getEncoder().encodeToString(
                    (username + ":" + password).getBytes(StandardCharsets.UTF_8));
            result.put("success", true);
            result.put("token", token);
            return ResponseEntity.ok(result);
        } else {
            log.warn("Login failed: username={}", username);
            result.put("success", false);
            result.put("message", "Invalid username or password");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(result);
        }
    }

    /**
     * 验证 Authorization 头中的凭据
     *
     * @param authorizationHeader Authorization 请求头
     * @param gatewayConfig 网关配置
     * @return 是否认证通过
     */
    private boolean isAuthenticated(String authorizationHeader, Map<String, Object> gatewayConfig) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Basic ")) {
            return false;
        }

        String base64Credentials = authorizationHeader.substring("Basic ".length());
        String credentials;
        try {
            credentials = new String(Base64.getDecoder().decode(base64Credentials), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return false;
        }

        int colonIndex = credentials.indexOf(':');
        if (colonIndex < 0) {
            return false;
        }

        String inputUsername = credentials.substring(0, colonIndex);
        String inputPassword = credentials.substring(colonIndex + 1);

        return username(gatewayConfig).equals(inputUsername)
                && password(gatewayConfig).equals(inputPassword);
    }

    // ==================== Redis 网关配置读取 ====================

    @SuppressWarnings("unchecked")
    private Map<String, Object> gatewayConfig() {
        Map<String, Object> cfg = store.getJSON(RedisKeys.UI_GATEWAY_CONFIG, Map.class);
        return cfg != null ? cfg : Collections.emptyMap();
    }

    private boolean isAuthEnabled(Map<String, Object> gatewayConfig) {
        return Boolean.TRUE.equals(gatewayConfig.get("authEnabled"));
    }

    private String username(Map<String, Object> gatewayConfig) {
        Object v = gatewayConfig.get("username");
        return v != null ? v.toString() : "";
    }

    private String password(Map<String, Object> gatewayConfig) {
        Object v = gatewayConfig.get("password");
        return v != null ? v.toString() : "";
    }
}
