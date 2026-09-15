package cn.seifly.jharness.plugin.app.core;

import cn.seifly.jharness.plugin.framework.web.PluginControllerRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于 Spring MVC 的插件控制器注册器（主应用实现）。
 *
 * <p>核心原理：插件 ClassLoader 加载的 {@code @RestController} 类不会进入主应用
 * 组件扫描，这里通过 {@link RequestMappingHandlerMapping#registerMapping} 在运行时
 * 将控制器方法直接挂载到主应用 MVC 路由表（HandlerMethod 由反射调用，可处理
 * {@code @PathVariable} / {@code @RequestParam} / {@code @RequestBody} 等参数）。
 * 插件停止时 {@link #unregister} 移除全部映射，实现接口热插拔。
 */
@Slf4j
@Component
public class SpringMvcPluginControllerRegistry implements PluginControllerRegistry {

    private final RequestMappingHandlerMapping handlerMapping;
    private final ApplicationContext applicationContext;

    /** 控制器实例 -> 已注册的（方法 -> RequestMappingInfo）列表，用于注销 */
    private final Map<Object, List<MethodMapping>> registered = new ConcurrentHashMap<>();

    public SpringMvcPluginControllerRegistry(RequestMappingHandlerMapping handlerMapping,
                                             ApplicationContext applicationContext) {
        this.handlerMapping = handlerMapping;
        this.applicationContext = applicationContext;
    }

    @Override
    public void register(Object controller) {
        if (controller == null) {
            return;
        }
        // 1. 插件 Controller 同样支持注入主应用 Bean（如 Service / Mapper / 配置）
        applicationContext.getAutowireCapableBeanFactory().autowireBean(controller);

        Class<?> type = controller.getClass();
        RequestMapping typeAnnotation = AnnotatedElementUtils.findMergedAnnotation(type, RequestMapping.class);
        if (typeAnnotation == null) {
            log.warn("插件控制器 [{}] 缺少类级 @RequestMapping，已跳过注册", type.getName());
            return;
        }

        RequestMappingInfo typeInfo = createInfo(typeAnnotation);

        List<MethodMapping> mappings = new ArrayList<>();
        for (Method method : type.getDeclaredMethods()) {
            RequestMapping methodAnnotation = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
            if (methodAnnotation == null) {
                continue;
            }
            // 类级条件与方法级条件合并（方法未写 path 时继承类级 path）
            RequestMappingInfo combined = typeInfo.combine(createInfo(methodAnnotation));
            handlerMapping.registerMapping(combined, controller, method);
            mappings.add(new MethodMapping(method, combined));
            log.info("已注册插件接口: {} {}", combined.getPathPatternsCondition(), method.getName());
        }

        if (mappings.isEmpty()) {
            log.warn("插件控制器 [{}] 没有可注册的 @RequestMapping 方法", type.getName());
            return;
        }
        registered.put(controller, mappings);
    }

    @Override
    public void unregister(Object controller) {
        if (controller == null) {
            return;
        }
        List<MethodMapping> mappings = registered.remove(controller);
        if (mappings == null) {
            if (log.isDebugEnabled()) {
                log.debug("插件控制器 [{}] 未注册或已注销，忽略", controller.getClass().getName());
            }
            return;
        }
        for (MethodMapping mapping : mappings) {
            handlerMapping.unregisterMapping(mapping.info);
            log.info("已注销插件接口: {}", mapping.info.getPathPatternsCondition());
        }
        log.info("插件控制器 [{}] 共注销 {} 个接口", controller.getClass().getName(), mappings.size());
    }

    /** 根据注解构建 RequestMappingInfo（类级 / 方法级通用） */
    private RequestMappingInfo createInfo(RequestMapping annotation) {
        RequestMappingInfo.BuilderConfiguration config = new RequestMappingInfo.BuilderConfiguration();
        config.setContentNegotiationManager(handlerMapping.getContentNegotiationManager());
        return RequestMappingInfo
                .paths(annotation.path())
                .methods(annotation.method())
                .params(annotation.params())
                .headers(annotation.headers())
                .consumes(annotation.consumes())
                .produces(annotation.produces())
                .options(config)
                .build();
    }

    /** 一条已注册的映射记录 */
    private record MethodMapping(Method method, RequestMappingInfo info) {
    }
}
