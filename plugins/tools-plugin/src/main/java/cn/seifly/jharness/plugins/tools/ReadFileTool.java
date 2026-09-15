package cn.seifly.jharness.plugins.tools;

import cn.seifly.jharness.plugin.framework.tools.Tool;
import cn.seifly.jharness.plugin.framework.tools.ToolException;

import cn.seifly.jharness.plugin.framework.security.SecurityGuard;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * 文件读取工具
 *
 * 允许Agent读取本地文件系统中的文件内容。
 */
public class ReadFileTool implements Tool {

    private static final long MAX_FILE_SIZE_BYTES = 10 * 1024 * 1024; // 10MB

    private final SecurityGuard securityGuard;

    public ReadFileTool() {
        this.securityGuard = null;
    }

    public ReadFileTool(SecurityGuard securityGuard) {
        this.securityGuard = securityGuard;
    }

    @Override
    public String name() {
        return "read_file";
    }

    @Override
    public String description() {
        return "读取文件内容";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> params = new HashMap<>();
        params.put("type", "object");

        Map<String, Object> properties = new HashMap<>();
        Map<String, Object> pathParam = new HashMap<>();
        pathParam.put("type", "string");
        pathParam.put("description", "要读取的文件路径");
        properties.put("path", pathParam);

        params.put("properties", properties);
        params.put("required", new String[]{"path"});

        return params;
    }

    @Override
    public String execute(Map<String, Object> args) throws ToolException {
        String path = (String) args.get("path");
        if (path == null || path.isEmpty()) {
            throw new IllegalArgumentException("路径参数是必需的");
        }

        // 安全检查
        if (securityGuard != null) {
            String error = securityGuard.checkFilePath(path);
            if (error != null) {
                throw new SecurityException(error);
            }
        }

        try {
            Path filePath = Paths.get(path);

            // 检查文件是否存在
            if (!Files.exists(filePath)) {
                return "文件不存在: " + path;
            }

            // 检查是否为常规文件
            if (!Files.isRegularFile(filePath)) {
                return "路径不是一个常规文件: " + path;
            }

            // 检查文件大小
            long fileSize = Files.size(filePath);
            if (fileSize > MAX_FILE_SIZE_BYTES) {
                return "文件过大（" + fileSize + " 字节），超过最大限制 " + MAX_FILE_SIZE_BYTES + " 字节";
            }
            return Files.readString(filePath);
        } catch (IOException e) {
            throw new ToolException("读取文件失败: " + e.getMessage(), e);
        }
    }
}
