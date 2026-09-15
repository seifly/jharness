package cn.seifly.jharness.plugin.app.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MVC 入参日志拦截器。
 *
 * <p>在 {@code preHandle} 阶段以 INFO 打印本次请求的基本入参：HTTP 方法、URI、查询串、
 * 路径变量、表单参数；对文件上传（multipart）仅打印文件名与大小等元数据，<b>不打印文件内容</b>。
 * {@code afterCompletion} 阶段打印响应状态码（及异常信息）。
 *
 * <p>纯文本 body 的入参（如 {@code @RequestBody} 的 JSON）由 {@link MvcPayloadLoggingAdvice} 处理。
 */
@Slf4j
public class MvcLoggingInterceptor implements HandlerInterceptor {

    /** 单条日志允许打印的最大长度（超过截断） */
    private static final int MAX_LENGTH = 1500;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod)) {
            // 静态资源、favicon 等非控制器方法不记录
            return true;
        }

        StringBuilder sb = new StringBuilder();
        sb.append(request.getMethod()).append(' ').append(request.getRequestURI());

        String query = request.getQueryString();
        if (query != null && !query.isEmpty()) {
            sb.append('?').append(truncate(query));
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> pathVars =
                (Map<String, Object>) request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (pathVars != null && !pathVars.isEmpty()) {
            sb.append(" pathVars=").append(pathVars);
        }

        String contentType = request.getContentType();
        if (contentType != null && contentType.toLowerCase().startsWith("multipart/")) {
            // 文件上传：仅记录元数据，绝不打印文件内容
            sb.append(" [文件上传]");
            try {
                for (Part part : request.getParts()) {
                    if (part.getSubmittedFileName() != null) {
                        sb.append(" file=").append(part.getSubmittedFileName())
                                .append("(size=").append(part.getSize()).append(")");
                    } else {
                        sb.append(" formField=").append(part.getName());
                    }
                }
            } catch (Exception e) {
                sb.append(" (无法读取上传明细: ").append(e.getMessage()).append(')');
            }
        } else {
            Map<String, String> params = new LinkedHashMap<>();
            Enumeration<String> names = request.getParameterNames();
            while (names.hasMoreElements()) {
                String name = names.nextElement();
                params.put(name, request.getParameter(name));
            }
            if (!params.isEmpty()) {
                sb.append(" params=").append(params);
            }
        }

        log.info("[REQ] {}", sb);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        if (ex != null) {
            log.info("[REQ-DONE] {} 请求:{} => status={} (异常: {})",
                    request.getMethod(), request.getRequestURI(), response.getStatus(), ex);
        } else {
            log.info("[REQ-DONE] {} 请求:{} => status={}",
                    request.getMethod(), request.getRequestURI(), response.getStatus());
        }
    }

    private static String truncate(String str) {
        if (str == null) {
            return "null";
        }
        return str.length() <= MAX_LENGTH ? str : str.substring(0, MAX_LENGTH) + "...(truncated)";
    }
}
