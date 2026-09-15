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
 * 文件编辑工具 - 通过替换文本来编辑文件
 *
 * 通过查找并替换特定的文本片段来精确修改文件内容。
 * 这是比 write_file 更安全的编辑方式，因为它只修改需要更改的部分。
 */
public class EditFileTool implements Tool {

    private final SecurityGuard securityGuard;
    // 已废弃：使用 SecurityGuard 代替
    private final String allowedDir;

    /**
     * 创建无目录限制的编辑工具
     */
    public EditFileTool() {
        this.securityGuard = null;
        this.allowedDir = null;
    }

    /**
     * 创建带 SecurityGuard 的编辑工具
     */
    public EditFileTool(SecurityGuard securityGuard) {
        this.securityGuard = securityGuard;
        this.allowedDir = null;
    }

    /**
     * 创建带目录限制的编辑工具（已废弃：使用 SecurityGuard 代替）
     */
    @Deprecated
    public EditFileTool(String allowedDir) {
        this.securityGuard = null;
        this.allowedDir = allowedDir;
    }

    @Override
    public String name() {
        return "edit_file";
    }

    @Override
    public String description() {
        return "通过替换文本来编辑文件。old_text 必须完全匹配文件中的内容。";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> params = new HashMap<>();
        params.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> pathParam = new HashMap<>();
        pathParam.put("type", "string");
        pathParam.put("description", "要编辑的文件路径");
        properties.put("path", pathParam);

        Map<String, Object> oldTextParam = new HashMap<>();
        oldTextParam.put("type", "string");
        oldTextParam.put("description", "要查找并替换的确切文本");
        properties.put("old_text", oldTextParam);

        Map<String, Object> newTextParam = new HashMap<>();
        newTextParam.put("type", "string");
        newTextParam.put("description", "用于替换的文本");
        properties.put("new_text", newTextParam);

        params.put("properties", properties);
        params.put("required", new String[]{"path", "old_text", "new_text"});

        return params;
    }

    /**
     * 将路径解析为相对于 workspace 的绝对路径。
     */
    private String resolveAgainstWorkspace(String path) {
        if (securityGuard == null || Paths.get(path).isAbsolute()) {
            return path;
        }
        return Paths.get(securityGuard.getWorkspace(), path).normalize().toString();
    }

    @Override
    public String execute(Map<String, Object> args) throws ToolException {
        // 参数验证
        String path = (String) args.get("path");
        String oldText = (String) args.get("old_text");
        String newText = (String) args.get("new_text");

        if (path == null || path.isEmpty()) {
            throw new IllegalArgumentException("路径参数是必需的");
        }
        if (oldText == null) {
            throw new IllegalArgumentException("old_text 参数是必需的");
        }
        if (newText == null) {
            throw new IllegalArgumentException("new_text 参数是必需的");
        }

        // 将相对路径解析为相对于 workspace 的绝对路径
        String resolvedPathString = resolveAgainstWorkspace(path);
        Path resolvedPath = Paths.get(resolvedPathString).normalize();

        // 使用 SecurityGuard 进行安全检查（推荐）
        if (securityGuard != null) {
            String error = securityGuard.checkFilePath(resolvedPathString);
            if (error != null) {
                throw new SecurityException(error);
            }
        }
        // 使用 allowedDir 进行旧式检查
        else if (allowedDir != null && !allowedDir.isEmpty()) {
            Path allowedPath = Paths.get(allowedDir).toAbsolutePath().normalize();
            if (!resolvedPath.startsWith(allowedPath)) {
                throw new SecurityException("路径 " + path + " 在允许目录 " + allowedDir + " 之外");
            }
        }

        // 检查文件是否存在
        if (!Files.exists(resolvedPath)) {
            throw new IllegalArgumentException("文件未找到: " + path);
        }

        // 读取文件内容
        String content;
        try {
            content = Files.readString(resolvedPath);
        } catch (IOException e) {
            throw new ToolException("读取文件失败: " + e.getMessage(), e);
        }

        // 检查 old_text 是否存在
        if (!content.contains(oldText)) {
            throw new IllegalArgumentException("old_text 在文件中未找到。请确保它完全匹配，包括空格和换行符。");
        }

        // 检查 old_text 是否唯一
        int count = countOccurrences(content, oldText);
        if (count > 1) {
            throw new IllegalArgumentException("old_text 在文件中出现了 " + count + " 次。请提供更多上下文使其唯一。");
        }

        // 执行替换
        String newContent = content.replace(oldText, newText);

        // 写入文件
        try {
            Files.writeString(resolvedPath, newContent);
            return "成功编辑 " + path;
        } catch (IOException e) {
            throw new ToolException("写入文件失败: " + e.getMessage(), e);
        }
    }

    /**
     * 计算子字符串在文本中出现的次数
     */
    private int countOccurrences(String text, String substring) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(substring, index)) != -1) {
            count++;
            index += substring.length();
        }
        return count;
    }
}
