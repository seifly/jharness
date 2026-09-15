package cn.seifly.jharness.plugin.framework.security;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 安全守卫 - 工作空间沙箱和命令黑名单。
 *
 * <p>由 SDK 共享，供 workflow-plugin 与 tools-plugin 解耦：
 * <ul>
 *   <li>tools-plugin 的文件/命令类工具用其做路径/命令检查</li>
 *   <li>workflow-plugin 持有实例并按 AgentConfig 注入到各工具</li>
 * </ul>
 *
 * 提供两个主要的安全特性：
 * 1. 工作空间沙箱：限制文件操作在工作空间目录内
 * 2. 命令黑名单：阻止危险的 shell 命令
 */
@Slf4j(topic = "security")
public class SecurityGuard {

    private final String workspace;
    private volatile boolean restrictToWorkspace;
    private final List<Pattern> commandBlacklist;
    private final List<Path> protectedPaths;

    /**
     * 构造函数 - 使用默认命令黑名单
     *
     * @param workspace 工作空间目录路径
     * @param restrictToWorkspace 是否限制文件访问在工作空间内
     */
    public SecurityGuard(String workspace, boolean restrictToWorkspace) {
        this.workspace = normalizeWorkspacePath(workspace);
        this.restrictToWorkspace = restrictToWorkspace;
        this.commandBlacklist = buildDefaultCommandBlacklist();
        this.protectedPaths = buildDefaultProtectedPaths();

        log.info("SecurityGuard initialized: workspace={}, restrictToWorkspace={}, blacklistRules={}, protectedPaths={}",
            this.workspace, restrictToWorkspace, commandBlacklist.size(), protectedPaths.size());
    }

    /**
     * 构造函数 - 使用自定义命令黑名单
     *
     * @param workspace 工作空间目录路径
     * @param restrictToWorkspace 是否限制文件访问在工作空间内
     * @param customBlacklist 自定义命令黑名单模式
     */
    public SecurityGuard(String workspace, boolean restrictToWorkspace, List<String> customBlacklist) {
        this.workspace = normalizeWorkspacePath(workspace);
        this.restrictToWorkspace = restrictToWorkspace;
        this.commandBlacklist = buildCommandBlacklist(customBlacklist);
        this.protectedPaths = buildDefaultProtectedPaths();

        log.info("SecurityGuard initialized with custom blacklist: workspace={}, restrictToWorkspace={}, blacklistRules={}, protectedPaths={}",
            this.workspace, restrictToWorkspace, commandBlacklist.size(), protectedPaths.size());
    }

    /**
     * 检查文件路径是否允许访问
     */
    public String checkFilePath(String filePath) {
        if (filePath == null || filePath.isEmpty()) {
            return "File path is required";
        }

        try {
            Path resolvedPath = resolveRealPath(Paths.get(filePath));

            // 始终检查受保护文件（无论是否启用 workspace 限制）
            String protectedError = checkProtectedPath(resolvedPath, filePath);
            if (protectedError != null) {
                return protectedError;
            }

            if (!restrictToWorkspace) {
                return null;
            }

            Path workspacePath = resolveRealPath(Paths.get(workspace));

            if (!resolvedPath.startsWith(workspacePath)) {
                log.warn("File path blocked (outside workspace): path={}, resolved={}, workspace={}",
                    filePath, resolvedPath, workspace);
                return String.format(
                    "Access denied: Path '%s' is outside workspace '%s'",
                    filePath, workspace
                );
            }

            return null;

        } catch (Exception e) {
            log.error("Error checking file path: path={}, error={}", filePath, e.getMessage());
            return "Invalid file path: " + e.getMessage();
        }
    }

    private Path resolveRealPath(Path path) throws IOException {
        Path absolutePath = path.toAbsolutePath();

        if (absolutePath.toFile().exists()) {
            return absolutePath.toRealPath();
        }

        Path current = absolutePath;
        List<String> pendingParts = new ArrayList<>();

        while (current != null && !current.toFile().exists()) {
            pendingParts.add(0, current.getFileName().toString());
            current = current.getParent();
        }

        if (current == null) {
            return absolutePath.normalize();
        }

        Path realAncestor = current.toRealPath();
        Path result = realAncestor;
        for (String part : pendingParts) {
            result = result.resolve(part);
        }
        return result.normalize();
    }

    /**
     * 检查命令是否允许执行
     */
    public String checkCommand(String command) {
        if (command == null || command.isEmpty()) {
            return "Command is required";
        }

        for (Pattern pattern : commandBlacklist) {
            if (pattern.matcher(command).find()) {
                if (isWorkspaceExemptPattern(pattern) && isCommandWithinWorkspace(command)) {
                    log.info("Command allowed within workspace (exempt pattern): command={}, pattern={}",
                        command, pattern.pattern());
                    continue;
                }
                log.warn("Command blocked by blacklist: command={}, pattern={}",
                    command, pattern.pattern());
                return String.format(
                    "Command blocked by safety guard (dangerous pattern detected): %s",
                    pattern.pattern()
                );
            }
        }

        return null;
    }

    private boolean isWorkspaceExemptPattern(Pattern pattern) {
        String patternStr = pattern.pattern();
        return patternStr.contains("rm\\s+-[rf]")
            || patternStr.contains("del\\s+/[fq]")
            || patternStr.contains("rmdir\\s+/s");
    }

    private boolean isCommandWithinWorkspace(String command) {
        if (workspace == null || workspace.isEmpty()) {
            return false;
        }

        try {
            Path resolvedWorkspace = resolveRealPath(Paths.get(workspace));

            Pattern cdPattern = Pattern.compile("(?:^|&&|;|\\|\\|)\\s*cd\\s+([^\\s&;|]+)");
            Matcher cdMatcher = cdPattern.matcher(command);
            while (cdMatcher.find()) {
                String cdPath = cdMatcher.group(1);
                try {
                    Path resolvedCdPath = resolveRealPath(Paths.get(cdPath));
                    if (resolvedCdPath.startsWith(resolvedWorkspace)) {
                        return true;
                    }
                } catch (Exception ignored) {
                }
            }

            Pattern absolutePathPattern = Pattern.compile("(?<![\\w])((?:/[\\w.\\-]+)+)");
            Matcher pathMatcher = absolutePathPattern.matcher(command);
            boolean foundAbsolutePath = false;
            while (pathMatcher.find()) {
                String absolutePath = pathMatcher.group(1);
                foundAbsolutePath = true;
                try {
                    Path resolvedPath = resolveRealPath(Paths.get(absolutePath));
                    if (!resolvedPath.startsWith(resolvedWorkspace)) {
                        return false;
                    }
                } catch (Exception ignored) {
                    return false;
                }
            }

            return foundAbsolutePath;

        } catch (Exception e) {
            log.warn("Failed to check if command is within workspace: command={}, error={}",
                command, e.getMessage());
            return false;
        }
    }

    public String checkWorkingDir(String workingDir) {
        if (!restrictToWorkspace) {
            return null;
        }

        if (workingDir == null || workingDir.isEmpty()) {
            return null;
        }

        return checkFilePath(workingDir);
    }

    public String getWorkspace() {
        return workspace;
    }

    public boolean isRestrictToWorkspace() {
        return restrictToWorkspace;
    }

    private String checkProtectedPath(Path resolvedPath, String originalPath) {
        for (Path protectedPath : protectedPaths) {
            if (resolvedPath.equals(protectedPath)) {
                log.warn("File path blocked (protected sensitive file): path={}, resolved={}, protectedPath={}",
                    originalPath, resolvedPath, protectedPath);
                return String.format(
                    "Access denied: '%s' is a protected sensitive file and cannot be read or modified",
                    originalPath
                );
            }
        }
        return null;
    }

    private List<Path> buildDefaultProtectedPaths() {
        String home = System.getProperty("user.home");
        List<Path> paths = new ArrayList<>();
        try {
            paths.add(resolveRealPath(Paths.get(home, ".jclaw", "config.json")));
            paths.add(resolveRealPath(Paths.get(home, ".jclaw", ".env")));
        } catch (IOException e) {
            log.warn("Failed to resolve real paths for protected files, falling back to normalize: error={}",
                e.getMessage());
            paths.add(Paths.get(home, ".jclaw", "config.json").toAbsolutePath().normalize());
            paths.add(Paths.get(home, ".jclaw", ".env").toAbsolutePath().normalize());
        }
        return paths;
    }

    private String normalizeWorkspacePath(String path) {
        if (path == null || path.isEmpty()) {
            return System.getProperty("user.home") + "/.jclaw/workspace";
        }

        if (path.startsWith("~")) {
            path = System.getProperty("user.home") + path.substring(1);
        }

        try {
            return Paths.get(path).toAbsolutePath().normalize().toString();
        } catch (Exception e) {
            log.error("Failed to normalize workspace path: path={}, error={}", path, e.getMessage());
            return path;
        }
    }

    private List<Pattern> buildDefaultCommandBlacklist() {
        List<String> defaultPatterns = List.of(
            "\\brm\\s+-[rf]{1,2}\\b",
            "\\bdel\\s+/[fq]\\b",
            "\\brmdir\\s+/s\\b",
            "\\b(format|mkfs|diskpart)\\b\\s",
            "\\bdd\\s+if=",
            ">\\s*/dev/sd[a-z]\\b",
            "\\b(shutdown|reboot|poweroff|halt)\\b",
            ":\\(\\)\\s*\\{.*\\};\\s*:",
            "\\b(curl|wget)\\s+.*\\|\\s*(sh|bash|zsh|python|perl|ruby)",
            "\\b(sudo|su)\\s+",
            "\\bkillall\\s+-9\\b",
            "\\bpkill\\s+-9\\b",
            "\\bcrontab\\s+-r\\b",
            "\\bexport\\s+LD_PRELOAD\\b",
            "\\b(insmod|rmmod|modprobe)\\b",
            "\\.jclaw[/\\\\]config\\.json",
            "\\.jclaw[/\\\\]\\.env"
        );

        return buildCommandBlacklist(defaultPatterns);
    }

    private List<Pattern> buildCommandBlacklist(List<String> patterns) {
        List<Pattern> compiled = new ArrayList<>();
        for (String pattern : patterns) {
            if (pattern == null || pattern.isEmpty()) {
                continue;
            }
            try {
                compiled.add(Pattern.compile(pattern, Pattern.CASE_INSENSITIVE));
            } catch (Exception e) {
                // 非合法正则，转义后按字面量匹配（兼容 UI 输入的普通命令文本）
                try {
                    compiled.add(Pattern.compile(Pattern.quote(pattern), Pattern.CASE_INSENSITIVE));
                    log.warn("Blacklist pattern is not valid regex, fallback to literal match: pattern={}, error={}",
                        pattern, e.getMessage());
                } catch (Exception e2) {
                    log.error("Failed to compile blacklist pattern (regex and literal both failed): pattern={}, error={}",
                        pattern, e2.getMessage());
                }
            }
        }
        return compiled;
    }

    public List<String> getBlacklistPatterns() {
        return commandBlacklist.stream()
            .map(Pattern::pattern)
            .toList();
    }

    public void updateCommandBlacklist(List<String> customBlacklist) {
        if (customBlacklist == null || customBlacklist.isEmpty()) {
            this.commandBlacklist.clear();
            this.commandBlacklist.addAll(buildDefaultCommandBlacklist());
            log.info("SecurityGuard: Reverted to default command blacklist: blacklistRules={}",
                commandBlacklist.size());
        } else {
            List<Pattern> newPatterns = buildCommandBlacklist(customBlacklist);
            this.commandBlacklist.clear();
            this.commandBlacklist.addAll(newPatterns);
            log.info("SecurityGuard: Updated command blacklist: blacklistRules={}",
                commandBlacklist.size());
        }
    }

    public void updateRestrictToWorkspace(boolean restrictToWorkspace) {
        this.restrictToWorkspace = restrictToWorkspace;
        log.info("SecurityGuard: restrictToWorkspace setting updated: restrictToWorkspace={}",
            restrictToWorkspace);
    }
}
