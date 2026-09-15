package cn.seifly.jharness.plugins.rag.controller;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;

/**
 * RAG 知识库管理 Web UI 控制器。
 *
 * <p>提供知识库管理界面（单页应用）的入口页面。HTML/CSS/JS 全部内联在
 * {@code resources/reg/index.html} 中，随插件 fat-jar 分发，宿主通过本控制器访问。
 *
 * <p><b>实现要点（参照 ui-plugin 的 WebResourceController）</b>：
 * <ul>
 *   <li><b>类级 {@code @RequestMapping} 必须存在</b>：宿主
 *       {@code SpringMvcPluginControllerRegistry} 在类级 {@code @RequestMapping} 为 null 时
 *       会直接跳过整个控制器的注册（导致全部接口 404）。此处声明但不指定 path，
 *       由方法级注解提供绝对路径。</li>
 *   <li><b>用插件自身 ClassLoader 读资源</b>：web 资源位于插件 classpath（PF4J 独立类加载器），
 *       主应用 {@code ClassPathResource} 读不到，必须用
 *       {@code getClass().getClassLoader().getResourceAsStream(...)}。</li>
 * </ul>
 *
 * <p>访问路径（两个入口都指向同一页面）：
 * <ul>
 *   <li>{@code GET /reg} 或 {@code GET /reg/} —— 与资源目录 {@code reg/} 对应</li>
 *   <li>{@code GET /rag} 或 {@code GET /rag/} —— 与插件名 rag-plugin 对应</li>
 * </ul>
 *
 * <p>数据 API 由 {@link RagController} 提供（{@code /api/rag/*}）。
 */
@RestController
@RequestMapping
public class RagWebController {

    /** 插件 classpath 中的页面资源路径 */
    private static final String INDEX_RESOURCE = "reg/index.html";

    /**
     * 知识库管理主页（单页应用）。
     */
    @GetMapping(value = {"/rag", "/rag/", "/reg", "/reg/"})
    public ResponseEntity<byte[]> index() {
        return serveHtml(INDEX_RESOURCE);
    }

    /**
     * 从插件 classpath 读取 HTML 并返回。
     */
    private ResponseEntity<byte[]> serveHtml(String resourcePath) {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("text/html; charset=utf-8"))
                    .cacheControl(CacheControl.noCache())
                    .body(in.readAllBytes());
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}
