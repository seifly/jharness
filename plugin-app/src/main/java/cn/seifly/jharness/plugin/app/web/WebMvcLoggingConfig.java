package cn.seifly.jharness.plugin.app.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 注册 MVC 入参日志拦截器 {@link MvcLoggingInterceptor}。
 *
 * <p>响应出参与 {@code @RequestBody} 请求体的日志由 {@link MvcPayloadLoggingAdvice}（{@code @ControllerAdvice}）完成。
 */
@Configuration
public class WebMvcLoggingConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new MvcLoggingInterceptor()).addPathPatterns("/**");
    }
}
