package cn.seifly.jharness.plugin.app.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.lang.reflect.Type;
import java.util.Set;

/**
 * MVC 请求体 / 响应体日志 Advice。
 *
 * <p>以 INFO 打印 {@code @RequestBody} 入参体 与 控制器响应出参体（统一序列化为 JSON 后截断到 1500 字符）。
 * 对文件上传（{@code MultipartFile}）及二进制/流类型跳过打印。
 *
 * <p>配合 {@link MvcLoggingInterceptor} 使用：拦截器负责方法 / URI / 查询参数 / 路径变量 / 上传元数据，
 * 本 Advice 负责 body 内容。
 */
@Slf4j
@ControllerAdvice
public class MvcPayloadLoggingAdvice implements RequestBodyAdvice, ResponseBodyAdvice<Object> {

    /** body 打印最大长度，超过截断 */
    private static final int MAX_LENGTH = 1500;

    /** 这些类型不打印 body（文件上传、二进制/流等） */
    private static final Set<Class<?>> SKIP_TYPES = Set.of(
            org.springframework.web.multipart.MultipartFile.class
    );

    private final ObjectMapper objectMapper;

    public MvcPayloadLoggingAdvice(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // ---------------------------------------------------------------- RequestBody

    @Override
    public boolean supports(MethodParameter methodParameter, Type targetType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public HttpInputMessage beforeBodyRead(HttpInputMessage inputMessage, MethodParameter parameter,
                                            Type targetType,
                                            Class<? extends HttpMessageConverter<?>> converterType) {
        return inputMessage;
    }

    @Override
    public Object afterBodyRead(Object body, HttpInputMessage inputMessage, MethodParameter parameter,
                                Type targetType, Class<? extends HttpMessageConverter<?>> converterType) {
        if (isSkipped(body)) {
            return body;
        }
        log.info("[REQ-BODY] {}.{} => {}",
                parameter.getDeclaringClass().getSimpleName(), parameter.getMethod().getName(),
                toLogString(body));
        return body;
    }

    @Override
    public Object handleEmptyBody(Object body, HttpInputMessage inputMessage, MethodParameter parameter,
                                  Type targetType, Class<? extends HttpMessageConverter<?>> converterType) {
        return body;
    }

    // ---------------------------------------------------------------- ResponseBody

    @Override
    public boolean supports(MethodParameter returnType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (isSkipped(body)) {
            return body;
        }
        log.info("[RES-BODY] {} 请求:{} => 返回参数:{}",
                request.getMethod(), request.getURI(), toLogString(body));
        return body;
    }

    // ---------------------------------------------------------------- 工具方法

    private boolean isSkipped(Object body) {
        if (body == null) {
            return true;
        }
        for (Class<?> type : SKIP_TYPES) {
            if (type.isInstance(body)) {
                return true;
            }
        }
        return false;
    }

    private String toLogString(Object body) {
        String str;
        if (body instanceof CharSequence cs) {
            str = cs.toString();
        } else {
            try {
                str = objectMapper.writeValueAsString(body);
            } catch (JsonProcessingException e) {
                str = String.valueOf(body);
            }
        }
        return truncate(str);
    }

    private static String truncate(String str) {
        if (str == null) {
            return "null";
        }
        return str.length() <= MAX_LENGTH ? str : str.substring(0, MAX_LENGTH) + "...(truncated,total=" + str.length() + ")";
    }
}
