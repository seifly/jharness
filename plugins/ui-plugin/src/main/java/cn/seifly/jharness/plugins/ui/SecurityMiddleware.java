package cn.seifly.jharness.plugins.ui;

import cn.seifly.jharness.plugin.framework.redis.ConfigStore;
import cn.seifly.jharness.plugin.framework.redis.RedisKeys;
import cn.seifly.jharness.plugin.framework.redis.RedisShared;
import com.sun.net.httpserver.HttpExchange;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 安全中间件，提供 CORS 预检、Basic Auth 认证和速率限制能力。
 *
 * <p>网关配置（CORS、认证、限流）不再通过 {@code Config} 跨插件注入，
 * 改为从 Redis 的 {@link RedisKeys#UI_GATEWAY_CONFIG} 读取。
 */
@Slf4j
public class SecurityMiddleware {

    private final ConfigStore store = RedisShared.store();

    private final AtomicInteger requestCount = new AtomicInteger(0);
    private volatile long rateLimitWindowStart = System.currentTimeMillis();

    public SecurityMiddleware() {
    }

    /**
     * 统一前置检查：CORS 预检 → 认证 → 速率限制。
     *
     * @return true 表示所有检查通过，false 表示已拦截（已发送响应）
     */
    public boolean preCheck(HttpExchange exchange) throws IOException {
        if (handleCorsPreFlight(exchange)) return false;
        if (!checkAuth(exchange)) return false;
        if (!checkRateLimit(exchange)) return false;
        return true;
    }

    /**
     * 处理 CORS 预检请求（OPTIONS）。
     *
     * @return true 表示是 OPTIONS 请求且已处理
     */
    public boolean handleCorsPreFlight(HttpExchange exchange) throws IOException {
        if (WebUtils.HTTP_METHOD_OPTIONS.equals(exchange.getRequestMethod())) {
            String corsOrigin = corsOrigin();
            exchange.getResponseHeaders().set(WebUtils.HEADER_CORS, corsOrigin);
            exchange.getResponseHeaders().set(WebUtils.HEADER_CORS_HEADERS, WebUtils.HEADER_CORS_HEADERS_VALUE);
            exchange.getResponseHeaders().set(WebUtils.HEADER_CORS_METHODS, WebUtils.HEADER_CORS_METHODS_VALUE);
            exchange.sendResponseHeaders(204, -1);
            return true;
        }
        return false;
    }

    /**
     * 检查 Basic Auth 认证。
     *
     * @return true 表示认证通过（或未启用认证），false 表示认证失败（已发送 401 响应）
     */
    public boolean checkAuth(HttpExchange exchange) throws IOException {
        if (!isAuthEnabled()) {
            return true;
        }

        String authHeader = exchange.getRequestHeaders().getFirst(WebUtils.HEADER_AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Basic ")) {
            sendAuthChallenge(exchange);
            return false;
        }

        String base64Credentials = authHeader.substring("Basic ".length());
        String credentials;
        try {
            credentials = new String(Base64.getDecoder().decode(base64Credentials), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            sendAuthChallenge(exchange);
            return false;
        }

        int colonIndex = credentials.indexOf(':');
        if (colonIndex < 0) {
            sendAuthChallenge(exchange);
            return false;
        }

        String inputUsername = credentials.substring(0, colonIndex);
        String inputPassword = credentials.substring(colonIndex + 1);

        if (username().equals(inputUsername) && password().equals(inputPassword)) {
            return true;
        }

        log.warn("Authentication failed: username={}", inputUsername);
        sendAuthChallenge(exchange);
        return false;
    }

    /**
     * 发送 401 认证失败响应（不带 WWW-Authenticate 头，避免触发浏览器原生弹窗）。
     */
    public void sendAuthChallenge(HttpExchange exchange) throws IOException {
        WebUtils.sendJson(exchange, 401, WebUtils.errorJson("Authentication required"), corsOrigin());
    }

    /**
     * 检查请求速率限制（每分钟滑动窗口）。
     *
     * @return true 表示未超限，false 表示已超限（已发送 429 响应）
     */
    public boolean checkRateLimit(HttpExchange exchange) throws IOException {
        if (!isRateLimitEnabled()) {
            return true;
        }

        long now = System.currentTimeMillis();
        long windowStart = rateLimitWindowStart;

        if (now - windowStart >= 60_000) {
            rateLimitWindowStart = now;
            requestCount.set(0);
        }

        int currentCount = requestCount.incrementAndGet();
        int limit = rateLimitPerMinute();
        if (currentCount > limit) {
            log.warn("Rate limit exceeded: count={}, limit={}", currentCount, limit);
            WebUtils.sendJson(exchange, 429, WebUtils.errorJson("Rate limit exceeded. Try again later."),
                    corsOrigin());
            return false;
        }

        return true;
    }

    // ==================== Redis 网关配置读取 ====================

    /**
     * 读取网关配置 Map（key 为 {@link RedisKeys#UI_GATEWAY_CONFIG}）。
     * 不存在时返回空 Map（采用宽松默认值，便于在未配置时仍可访问）。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> gatewayConfig() {
        Map<String, Object> cfg = store.getJSON(RedisKeys.UI_GATEWAY_CONFIG, Map.class);
        return cfg != null ? cfg : java.util.Collections.emptyMap();
    }

    private String corsOrigin() {
        Object v = gatewayConfig().get("corsOrigin");
        return (v instanceof String s && !s.isEmpty()) ? s : "*";
    }

    private boolean isAuthEnabled() {
        Object v = gatewayConfig().get("authEnabled");
        return Boolean.TRUE.equals(v);
    }

    private String username() {
        Object v = gatewayConfig().get("username");
        return v != null ? v.toString() : "";
    }

    private String password() {
        Object v = gatewayConfig().get("password");
        return v != null ? v.toString() : "";
    }

    private boolean isRateLimitEnabled() {
        Object v = gatewayConfig().get("rateLimitEnabled");
        return Boolean.TRUE.equals(v);
    }

    private int rateLimitPerMinute() {
        Object v = gatewayConfig().get("rateLimitPerMinute");
        if (v instanceof Number n) {
            return n.intValue();
        }
        return 60;
    }
}
