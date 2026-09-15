package cn.seifly.jharness.plugins.tools;

import cn.seifly.jharness.plugin.framework.tools.Tool;
import cn.seifly.jharness.plugin.framework.tools.ToolException;

import cn.seifly.jharness.plugin.framework.security.SecurityGuard;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Shell 命令执行工具。
 *
 * 允许 AI Agent 执行系统命令，支持跨平台（Windows/Linux/macOS）。
 *
 * 安全警告：
 * 此工具允许执行任意系统命令，请务必配合 SecurityGuard 使用，
 * 避免执行危险命令（如 rm -rf、格式化等）。
 */
@Slf4j
public class ExecTool implements Tool {

    private static final int MAX_OUTPUT_LENGTH = 10000;         // 输出最大长度
    private static final long DEFAULT_TIMEOUT_SECONDS = 60;     // 默认超时时间（秒）
    private static final long THREAD_JOIN_TIMEOUT_MS = 1000;    // 线程等待超时（毫秒）

    private final SecurityGuard securityGuard;   // 安全守卫（可选）
    private final String workingDir;             // 默认工作目录
    private final long timeoutSeconds;           // 命令超时时间

    public ExecTool(String workingDir) {
        this(workingDir, null);
    }

    public ExecTool(String workingDir, SecurityGuard securityGuard) {
        this.securityGuard = securityGuard;
        this.workingDir = workingDir;
        this.timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
    }

    @Override
    public String name() {
        return "exec";
    }

    @Override
    public String description() {
        return "执行 Shell 命令并返回输出。请谨慎使用。";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> params = new HashMap<>();
        params.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> commandParam = new HashMap<>();
        commandParam.put("type", "string");
        commandParam.put("description", "要执行的 Shell 命令");
        properties.put("command", commandParam);

        Map<String, Object> workingDirParam = new HashMap<>();
        workingDirParam.put("type", "string");
        workingDirParam.put("description", "命令的可选工作目录");
        properties.put("working_dir", workingDirParam);

        params.put("properties", properties);
        params.put("required", new String[]{"command"});

        return params;
    }

    @Override
    public String execute(Map<String, Object> args) throws ToolException {
        String command = (String) args.get("command");
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("命令参数是必需的");
        }

        // 解析工作目录
        String cwd = resolveWorkingDir((String) args.get("working_dir"));

        // 安全检查
        String securityError = performSecurityChecks(command, cwd);
        if (securityError != null) {
            return "错误: " + securityError;
        }

        log.info("Executing command: command={}, cwd={}", command, cwd);

        // 执行命令并获取结果
        try {
            return executeCommand(command, cwd);
        } catch (Exception e) {
            throw new ToolException("执行命令失败: " + e.getMessage(), e);
        }
    }

    /**
     * 解析工作目录。
     */
    private String resolveWorkingDir(String workingDirArg) {
        if (workingDirArg != null && !workingDirArg.isEmpty()) {
            return workingDirArg;
        }
        if (workingDir != null && !workingDir.isEmpty()) {
            return workingDir;
        }
        return System.getProperty("user.dir");
    }

    /**
     * 执行安全检查。
     */
    private String performSecurityChecks(String command, String cwd) {
        // 检查工作目录
        if (securityGuard != null) {
            String error = securityGuard.checkWorkingDir(cwd);
            if (error != null) {
                return error;
            }
        }

        // 检查命令安全性
        return guardCommand(command);
    }

    /**
     * 执行命令并捕获输出。
     */
    private String executeCommand(String command, String cwd) throws Exception {
        // 构建进程
        Process process = buildProcess(command, cwd);

        // 捕获输出
        CommandOutput output = captureOutput(process);

        // 等待进程完成
        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);

        // 等待输出读取线程完成
        output.waitForThreads();

        // 处理超时
        if (!finished) {
            process.destroyForcibly();
            try {
                process.waitFor(30, TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            return "错误: 命令超时，超过 " + timeoutSeconds + " 秒";
        }

        // 构建结果
        return buildResult(output, process.exitValue());
    }

    /**
     * 构建命令执行进程。
     */
    private Process buildProcess(String command, String cwd) throws Exception {
        String[] shellCmd = getShellCommand(command);

        ProcessBuilder pb = new ProcessBuilder(shellCmd);
        pb.directory(Paths.get(cwd).toFile());
        pb.redirectErrorStream(false);

        return pb.start();
    }

    /**
     * 获取适合当前操作系统的 Shell 命令。
     */
    private String[] getShellCommand(String command) {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            return new String[]{"cmd", "/c", command};
        } else {
            return new String[]{"sh", "-c", command};
        }
    }

    /**
     * 捕获命令输出。
     */
    private CommandOutput captureOutput(Process process) {
        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();

        Thread stdoutThread = createOutputThread(process.getInputStream(), stdout, "exec-stdout");
        Thread stderrThread = createOutputThread(process.getErrorStream(), stderr, "exec-stderr");

        stdoutThread.start();
        stderrThread.start();

        return new CommandOutput(stdout, stderr, stdoutThread, stderrThread);
    }

    /**
     * 创建输出读取线程。
     */
    private Thread createOutputThread(InputStream inputStream,
                                      StringBuilder output, String threadName) {
        return new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (output) {
                        output.append(line).append("\n");
                    }
                }
            } catch (Exception e) {
                if (log.isDebugEnabled()) {
                    log.debug("Output reader thread exception: thread={}, error={}",
                            threadName, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                }
            }
        }, threadName);
    }

    /**
     * 构建执行结果。
     */
    private String buildResult(CommandOutput output, int exitCode) {
        String result = output.getStdout();

        // 添加 stderr
        if (!output.getStderr().isEmpty()) {
            result += "\nSTDERR:\n" + output.getStderr();
        }

        // 添加退出代码
        if (exitCode != 0) {
            result += "\n退出代码: " + exitCode;
        }

        // 处理空输出
        if (result.isEmpty()) {
            result = "(无输出)";
        }

        // 截断过长输出
        return truncateIfNeeded(result);
    }

    /**
     * 截断过长的输出。
     */
    private String truncateIfNeeded(String result) {
        if (result.length() > MAX_OUTPUT_LENGTH) {
            int remaining = result.length() - MAX_OUTPUT_LENGTH;
            return result.substring(0, MAX_OUTPUT_LENGTH)
                    + "\n... (已截断，还有 " + remaining + " 个字符)";
        }
        return result;
    }

    /**
     * 检查命令安全性。
     */
    private String guardCommand(String command) {
        if (securityGuard != null) {
            return securityGuard.checkCommand(command);
        }

        // 未启用 SecurityGuard 时发出警告
        log.warn("命令执行未启用 SecurityGuard，存在安全风险");
        return null;
    }

    /**
     * 命令输出封装类。
     */
    private static class CommandOutput {
        private final StringBuilder stdout;
        private final StringBuilder stderr;
        private final Thread stdoutThread;
        private final Thread stderrThread;

        CommandOutput(StringBuilder stdout, StringBuilder stderr,
                     Thread stdoutThread, Thread stderrThread) {
            this.stdout = stdout;
            this.stderr = stderr;
            this.stdoutThread = stdoutThread;
            this.stderrThread = stderrThread;
        }

        String getStdout() {
            synchronized (stdout) {
                return stdout.toString();
            }
        }

        String getStderr() {
            synchronized (stderr) {
                return stderr.toString();
            }
        }

        void waitForThreads() {
            try {
                stdoutThread.join(THREAD_JOIN_TIMEOUT_MS);
                stderrThread.join(THREAD_JOIN_TIMEOUT_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
