package cn.seifly.jharness.plugins.ui.controller;

import cn.seifly.jharness.plugin.framework.web.ApiResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 健康检查和系统信息控制器
 *
 * 提供Spring Boot Web模式下的基本系统信息和健康检查端点。
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    // TODO 版本号原取自 JClawApplication.VERSION（宿主入口类），跨插件迁移后暂以静态值占位，
    //      待后续统一从宿主提供的扩展点或 Redis 中获取。
    private static final String APP_VERSION = "1.0.0";

    /**
     * 健康检查端点
     *
     * @return 健康状态信息
     */
    @GetMapping("/health")
    public ApiResult<Map<String, Object>> health() {
        Map<String, Object> result = new HashMap<>();
        result.put("status", "UP");
        result.put("timestamp", LocalDateTime.now().toString());
        result.put("version", APP_VERSION);
        return ApiResult.ok(result);
    }

    /**
     * 系统信息端点
     *
     * @return 系统基本信息
     */
    @GetMapping("/info")
    public ApiResult<Map<String, Object>> info() {
        Map<String, Object> result = new HashMap<>();
        result.put("name", "jclaw");
        result.put("version", APP_VERSION);
        result.put("description", "超轻量个人AI助手");
        result.put("timestamp", LocalDateTime.now().toString());
        return ApiResult.ok(result);
    }
}
