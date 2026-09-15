package cn.seifly.jharness.plugins.hello.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * hello-plugin 插件自带的 REST 控制器。
 *
 * <p>注意：插件里的 {@code @RestController} 不会进入主应用组件扫描，
 * 由 {@link HelloPlugin} 在 start() 中通过 PluginControllerRegistry 动态挂载到 Spring MVC；
 * stop() 时自动卸载。
 */
@RestController
@RequestMapping("/api/hello")
public class HelloController {

    /** 示例 GET 接口：http://localhost:8080/api/hello/say?name=seifly */
    @GetMapping("/say")
    public Map<String, Object> say(@RequestParam(defaultValue = "world") String name) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("message", "你好, " + name + "！我是来自 hello-plugin 插件的 REST 接口");
        data.put("from", "hello-plugin");
        data.put("time", LocalDateTime.now().toString());
        return data;
    }

    /** 示例 POST 接口：echo 请求体中的 name 字段 */
    @GetMapping("/ping")
    public Map<String, Object> ping() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("message", "pong");
        data.put("time", LocalDateTime.now().toString());
        return data;
    }
}
