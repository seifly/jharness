package cn.seifly.jharness.plugins.ui.controller;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Web Console 静态资源控制器。
 *
 * <p>ui-plugin 的 web/ 资源位于插件自身 classpath（PF4J 独立类加载器），主应用
 * {@code classpath:/web/} 无法直接读取；同时插件类不进主应用组件扫描，主应用的
 * {@code @Configuration + WebMvcConfigurer} 也无法挂载插件资源。因此这里以普通
 * REST 控制器形式承载静态资源，由 {@code UiPlugin} 在 start() 中通过
 * {@link cn.seifly.jharness.plugin.framework.web.PluginControllerRegistry} 动态挂载到
 * 主应用 Spring MVC，stop() 时卸载。
 *
 * <p>URL 映射（与旧版主应用 {@code WebResourceConfiguration} 一致）：
 * <ul>
 *   <li>{@code /web/**} —— Agent 聊天控制台入口，缺省返回 index.html</li>
 *   <li>{@code /css/**} —— index.html 内以根路径引用的样式，缺省 style.css</li>
 *   <li>{@code /js/**} —— index.html 内以根路径引用的脚本，缺省 app.js</li>
 *   <li>{@code /sw.js} —— PWA Service Worker</li>
 * </ul>
 *
 * <p>资源从插件 classpath 的 {@code web/} 目录读取（打包进插件 jar 后同样有效，
 * 不再依赖源码目录文件系统路径）。缺点：每次请求逐请求读流并返回，不做 Spring
 * 资源缓存/范围请求；对开发期小体量前端已足够。
 */
@RestController
@RequestMapping
public class WebResourceController {

    /** 插件 classpath 中 web 资源根目录（相对插件 classpath 根） */
    private static final String WEB_ROOT = "web/";

    /** /web 缺省入口 */
    private static final String DEFAULT_INDEX = "index.html";
    /** /css 缺省入口（index.html 根路径引用） */
    private static final String DEFAULT_CSS = "style.css";
    /** /js 缺省入口（index.html 根路径引用） */
    private static final String DEFAULT_JS = "app.js";

    /** web 子资源目录：/css/** -> web/css/，/js/** -> web/js/ */
    private static final String WEB_CSS_ROOT = "web/css/";
    private static final String WEB_JS_ROOT = "web/js/";

    @GetMapping("/web/{*path}")
    public ResponseEntity<byte[]> web(@PathVariable(name = "path", required = false) String path) {
        return serve(WEB_ROOT, path, DEFAULT_INDEX);
    }

    @GetMapping("/css/{*path}")
    public ResponseEntity<byte[]> css(@PathVariable(name = "path", required = false) String path) {
        return serve(WEB_CSS_ROOT, path, DEFAULT_CSS);
    }

    @GetMapping("/js/{*path}")
    public ResponseEntity<byte[]> js(@PathVariable(name = "path", required = false) String path) {
        return serve(WEB_JS_ROOT, path, DEFAULT_JS);
    }

    @GetMapping("/sw.js")
    public ResponseEntity<byte[]> serviceWorker() {
        return serve(WEB_ROOT, null, "sw.js");
    }

    /**
     * 从插件 classpath 读取资源并返回。
     *
     * @param base        资源在插件 classpath 中的根目录（以 / 结尾）
     * @param rawPath     来自 URL 的原始路径。注意 Spring PathPattern 的 catch-all 变量
     *                    {@code {*path}} 捕获值以 "/" 开头（如 /web/index.html 捕获为
     *                    "/index.html"），且可能为 null / 空串
     * @param defaultFile rawPath 规范化后为空时使用的缺省文件
     */
    private ResponseEntity<byte[]> serve(String base, String rawPath, String defaultFile) {
        String relative = normalize(rawPath);
        if (relative.isEmpty()) {
            relative = defaultFile;
        }
        if (!isSafe(relative)) {
            return ResponseEntity.badRequest().build();
        }
        String resourcePath = base + relative;
        // 用当前类（插件）的 ClassLoader 读取，确保命中插件 jar / classes 内的资源
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                return ResponseEntity.notFound().build();
            }
            byte[] body = in.readAllBytes();
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentTypeOf(relative)))
                    .cacheControl(CacheControl.noCache())
                    .body(body);
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * 规范化 URL 原始路径：统一反斜杠为斜杠，去掉前导斜杠，
     * 使 {@code {*path}} 捕获值（如 "/index.html"）变为相对路径（"index.html"）。
     */
    private String normalize(String rawPath) {
        if (rawPath == null) {
            return "";
        }
        String normalized = rawPath.replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    /** 防止路径穿越：拒绝父目录引用，并做最终 normalize 校验 */
    private boolean isSafe(String relative) {
        if (relative.contains("..")) {
            return false;
        }
        try {
            return !Path.of(relative).normalize().startsWith("..");
        } catch (InvalidPathException e) {
            return false;
        }
    }

    /** 简单扩展名 -> Content-Type 映射（开发期小体量静态资源足够） */
    private static String contentTypeOf(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".html") || lower.endsWith(".htm")) {
            return "text/html; charset=utf-8";
        }
        if (lower.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (lower.endsWith(".js") || lower.endsWith(".mjs")) {
            return "application/javascript; charset=utf-8";
        }
        if (lower.endsWith(".json") || lower.endsWith(".map")) {
            return "application/json; charset=utf-8";
        }
        if (lower.endsWith(".svg")) {
            return "image/svg+xml";
        }
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".gif")) {
            return "image/gif";
        }
        if (lower.endsWith(".ico")) {
            return "image/x-icon";
        }
        if (lower.endsWith(".woff")) {
            return "font/woff";
        }
        if (lower.endsWith(".woff2")) {
            return "font/woff2";
        }
        if (lower.endsWith(".txt")) {
            return "text/plain; charset=utf-8";
        }
        return "application/octet-stream";
    }
}
