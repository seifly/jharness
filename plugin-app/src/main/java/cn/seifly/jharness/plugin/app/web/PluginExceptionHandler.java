package cn.seifly.jharness.plugin.app.web;

import org.pf4j.PluginRuntimeException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理：把 PF4J 异常 / 参数错误 / 资源不存在转换为统一的 JSON 响应。
 */
@RestControllerAdvice
public class PluginExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ApiResult<Void> badRequest(IllegalArgumentException e) {
        return ApiResult.error(400, e.getMessage());
    }

    @ExceptionHandler(PluginRuntimeException.class)
    public ApiResult<Void> pluginError(PluginRuntimeException e) {
        return ApiResult.error(500, "插件操作失败: " + e.getMessage());
    }

    /**
     * 未匹配到任何路由（含插件已停止导致接口注销的场景），返回 404。
     * 若不单独处理，会被下面的兜底 Exception 误报为 500。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ApiResult<Void> notFound(NoResourceFoundException e) {
        return ApiResult.error(404, "资源不存在: " + e.getResourcePath());
    }

    @ExceptionHandler(Exception.class)
    public ApiResult<Void> serverError(Exception e) {
        return ApiResult.error(500, "服务器内部错误: " + e.getMessage());
    }
}
