package cn.seifly.jharness.plugins.ui.controller;

import cn.seifly.jharness.plugins.ui.WebUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 文件访问 API 控制器
 *
 * 用于访问 workspace 下的文件（如上传的图片）。
 *
 * 安全限制：
 * - 只允许访问 workspace 目录下的文件
 * - 禁止路径遍历攻击（..）
 *
 * 注意：此接口不做 Auth 认证，因为 <img src> 等浏览器直接请求不会携带 Authorization header。
 * 安全性由路径遍历防护和 workspace 边界检查保证。
 *
 * <p>迁移说明：workspace 路径通过 {@code @Value} 注入（{@link WebUtils#expandHome} 展开 ~），
 * 不再注入跨插件 {@code Config}。
 */
@RestController
@RequestMapping("/api/files")
@CrossOrigin(origins = "${jclaw.gateway.cors-origin:*}", allowedHeaders = "*", methods = {RequestMethod.GET, RequestMethod.OPTIONS})
@Slf4j
public class FilesController {

    /** 缓存控制：图片缓存 1 小时 */
    private static final CacheControl CACHE_CONTROL = CacheControl.maxAge(1, TimeUnit.HOURS).cachePublic();

    @Value("${jclaw.agent.workspace:~/.jclaw/workspace}")
    private String workspacePath;

    /**
     * 获取文件
     *
     * 请求格式：GET /api/files/{relativePath}
     * 例如：GET /api/files/uploads/1710756000_abc.jpg
     *
     * @param relativePath 文件相对路径（URL 编码）
     * @return 文件资源
     */
    @GetMapping("/{*relativePath}")
    public ResponseEntity<?> getFile(@PathVariable("relativePath") String relativePath) {
        try {
            // 处理路径：Spring 的 {*relativePath} 会包含前导斜杠，需要去掉
            if (relativePath.startsWith("/")) {
                relativePath = relativePath.substring(1);
            }

            // URL 解码
            relativePath = URLDecoder.decode(relativePath, StandardCharsets.UTF_8);

            // 安全检查：禁止路径遍历
            if (relativePath.contains("..") || relativePath.startsWith("/")) {
                log.warn("Path traversal attempt: path={}", relativePath);
                Map<String, Object> error = new HashMap<>();
                error.put("error", "Access denied");
                return ResponseEntity.status(403).body(error);
            }

            // 构建完整文件路径（展开 ~ 为用户主目录）
            String workspace = WebUtils.expandHome(workspacePath);
            Path filePath = Paths.get(workspace, relativePath).normalize();

            if (log.isDebugEnabled()) {
                log.debug("文件访问请求: relative_path={}, workspace={}, full_path={}",
                        relativePath, workspace, filePath.toString());
            }

            // 再次验证路径在 workspace 内
            Path workspacePath = Paths.get(workspace).normalize();
            if (!filePath.startsWith(workspacePath)) {
                log.warn("Path escape attempt: path={}", filePath.toString());
                Map<String, Object> error = new HashMap<>();
                error.put("error", "Access denied");
                return ResponseEntity.status(403).body(error);
            }

            // 检查文件是否存在且是普通文件
            if (!java.nio.file.Files.exists(filePath) || !java.nio.file.Files.isRegularFile(filePath)) {
                log.warn("文件不存在: relative_path={}, full_path={}",
                        relativePath, filePath.toString());
                Map<String, Object> error = new HashMap<>();
                error.put("error", "Not found");
                return ResponseEntity.status(404).body(error);
            }

            // 读取并返回文件
            Resource resource = new UrlResource(filePath.toUri());
            String contentType = getContentType(filePath.toString());

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .cacheControl(CACHE_CONTROL)
                    .body(resource);

        } catch (Exception e) {
            log.error("Files API error: error={}", e.getMessage());
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.status(500).body(error);
        }
    }

    /**
     * 根据文件路径获取 Content-Type。
     */
    private String getContentType(String path) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".ico")) return "image/x-icon";
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".txt") || lower.endsWith(".md")) return "text/plain; charset=utf-8";
        return "application/octet-stream";
    }
}
