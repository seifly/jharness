package cn.seifly.jharness.plugins.workflow;

import cn.seifly.jharness.plugin.framework.tools.ToolService;
import cn.seifly.jharness.plugin.framework.bus.InboundMessage;
import cn.seifly.jharness.plugin.framework.bus.MessageBus;
import cn.seifly.jharness.plugin.framework.llm.LlmService;
import cn.seifly.jharness.plugin.framework.llm.Message;
import cn.seifly.jharness.plugin.framework.llm.StreamEvent;
import cn.seifly.jharness.plugins.sessions.SessionManager;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 子代理管理器
 * 用于生成和跟踪子代理任务
 */
@Slf4j
public class SubagentManager {

    // 任务保留时间（默认1小时）
    private static final long TASK_RETENTION_MS = 60 * 60 * 1000;
    // 清理间隔（10分钟）
    private static final long CLEANUP_INTERVAL_MS = 10 * 60 * 1000;

    private static final int DEFAULT_MAX_ITERATIONS = 10;

    private final Map<String, SubagentTask> tasks = new ConcurrentHashMap<>();
    private final LlmService provider;
    private final MessageBus bus;
    private final String workspace;
    private final ToolService tools;
    private final String model;
    private final int maxIterations;
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final ExecutorService executor;
    private volatile long lastCleanup = System.currentTimeMillis();

    /**
     * 表示一个子代理任务
     */
    @Getter
    @Setter
    public static class SubagentTask {
        private String id;
        private String task;
        private String label;
        private String originChannel;
        private String originChatId;
        private String status;
        private String result;
        private long created;
    }

    public SubagentManager(LlmService provider, String workspace, MessageBus bus,
                           ToolService tools, String model, int maxIterations) {
        this.provider = provider;
        this.workspace = workspace;
        this.bus = bus;
        this.tools = tools;
        this.model = model;
        this.maxIterations = maxIterations > 0 ? maxIterations : DEFAULT_MAX_ITERATIONS;
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setName("subagent-pool-" + t.getId());
            return t;
        });
    }

    /**
     * 便捷构造器，使用默认配置
     */
    public SubagentManager(LlmService provider, String workspace, MessageBus bus, ToolService tools) {
        this(provider, workspace, bus, tools, provider.getDefaultModel(), DEFAULT_MAX_ITERATIONS);
    }

    /**
     * 同步生成子代理并等待执行完成，返回子代理的实际执行结果。
     */
    public String spawnAndWait(String task, String label) {
        return spawnAndWaitStream(task, label, null);
    }

    /**
     * 同步生成子代理并等待执行完成（流式版本）。
     */
    public String spawnAndWaitStream(String task, String label, LlmService.EnhancedStreamCallback callback) {
        maybeCleanupOldTasks();

        String taskId = "subagent-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "-" + nextId.getAndIncrement();

        SubagentTask subagentTask = new SubagentTask();
        subagentTask.setId(taskId);
        subagentTask.setTask(task);
        subagentTask.setLabel(label != null ? label : "");
        subagentTask.setOriginChannel("internal");
        subagentTask.setOriginChatId("sync");
        subagentTask.setStatus("running");
        subagentTask.setCreated(System.currentTimeMillis());

        tasks.put(taskId, subagentTask);

        log.info("Spawned sync subagent: task_id={}, label={}, task_preview={}",
                taskId, label != null ? label : "",
                task.length() > 50 ? task.substring(0, 50) + "..." : task);

        if (callback != null) {
            callback.onEvent(StreamEvent.subagentStart(taskId, task, label));
        }

        runTaskSyncWithStream(subagentTask, callback);

        if (callback != null) {
            boolean success = "completed".equals(subagentTask.getStatus());
            callback.onEvent(StreamEvent.subagentEnd(taskId, subagentTask.getResult(), success));
        }

        return subagentTask.getResult();
    }

    /**
     * 同步执行子代理任务（不通过 MessageBus 回传，直接将结果写入 task 对象）。
     */
    private void runTaskSync(SubagentTask task) {
        runTaskSyncWithStream(task, null);
    }

    /**
     * 同步执行子代理任务（流式版本）。
     */
    private void runTaskSyncWithStream(SubagentTask task, LlmService.EnhancedStreamCallback callback) {
        List<Message> messages = new ArrayList<>();
        messages.add(new Message("system",
                "你是一个子代理。独立完成给定的任务并报告结果。" +
                        "你可以使用提供的工具来完成任务。" +
                        "完成后，用简洁明了的方式汇报结果。"));
        messages.add(new Message("user", task.getTask()));

        String subagentSessionPath = Paths.get(workspace, "sessions", "subagent").toString();
        SessionManager subagentSessions = new SessionManager(subagentSessionPath);
        String sessionKey = "subagent:" + task.getId();

        try {
            ReActExecutor reActExecutor = new ReActExecutor(provider, tools, subagentSessions, model
                    , provider.getName(), maxIterations);

            String result;

            if (callback != null) {
                result = reActExecutor.executeStream(messages, sessionKey, chunk -> {
                    callback.onEvent(StreamEvent.subagentContent(task.getId(), chunk));
                });
            } else {
                result = reActExecutor.execute(messages, sessionKey);
            }

            task.setStatus("completed");
            task.setResult(result != null ? result : "任务已完成但无返回内容");

            log.info("Sync subagent task completed: task_id={}, result_length={}",
                    task.getId(), task.getResult().length());
        } catch (Exception e) {
            task.setStatus("failed");
            task.setResult("子代理执行失败: " + e.getMessage());
            log.error("Sync subagent task failed: task_id={}, error={}",
                    task.getId(), e.getMessage());
        }
    }

    /**
     * 异步生成一个新的子代理任务（fire-and-forget 模式）。
     */
    public String spawn(String task, String label, String originChannel, String originChatId) {
        maybeCleanupOldTasks();

        String taskId = "subagent-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "-" + nextId.getAndIncrement();

        SubagentTask subagentTask = new SubagentTask();
        subagentTask.setId(taskId);
        subagentTask.setTask(task);
        subagentTask.setLabel(label != null ? label : "");
        subagentTask.setOriginChannel(originChannel != null ? originChannel : "cli");
        subagentTask.setOriginChatId(originChatId != null ? originChatId : "direct");
        subagentTask.setStatus("running");
        subagentTask.setCreated(System.currentTimeMillis());

        tasks.put(taskId, subagentTask);

        executor.submit(() -> runTask(subagentTask));

        log.info("Spawned subagent: task_id={}, label={}, task_preview={}",
                taskId, label, task.length() > 50 ? task.substring(0, 50) + "..." : task);

        if (label != null && !label.isEmpty()) {
            return "已生成子代理 '" + label + "' 处理任务: " + task;
        }
        return "已生成子代理处理任务: " + task;
    }

    private void runTask(SubagentTask task) {
        task.setStatus("running");
        task.setCreated(System.currentTimeMillis());

        List<Message> messages = new ArrayList<>();
        messages.add(new Message("system",
                "你是一个子代理。独立完成给定的任务并报告结果。" +
                        "你可以使用提供的工具来完成任务。" +
                        "完成后，用简洁明了的方式汇报结果。"));
        messages.add(new Message("user", task.getTask()));

        String subagentSessionPath = Paths.get(workspace, "sessions", "subagent").toString();
        SessionManager subagentSessions = new SessionManager(subagentSessionPath);
        String sessionKey = "subagent:" + task.getId();

        try {
            ReActExecutor executor = new ReActExecutor(provider, tools, subagentSessions, model, provider.getName(), maxIterations);
            String result = executor.execute(messages, sessionKey);

            task.setStatus("completed");
            task.setResult(result != null ? result : "任务已完成但无返回内容");

            log.info("Subagent task completed: task_id={}, result_length={}",
                    task.getId(), task.getResult().length());
        } catch (Exception e) {
            task.setStatus("failed");
            task.setResult("错误: " + e.getMessage());
            log.error("Subagent task failed: task_id={}, error={}",
                    task.getId(), e.getMessage());
        } finally {
            sendTaskCompletion(task);
        }
    }

    /**
     * 发送任务完成通知
     */
    private void sendTaskCompletion(SubagentTask task) {
        if (bus == null) {
            return;
        }

        String announceContent;
        if (task.getLabel() != null && !task.getLabel().isEmpty()) {
            announceContent = "任务 '" + task.getLabel() + "' 已完成。\n\n结果:\n" + task.getResult();
        } else {
            announceContent = "任务已完成。\n\n结果:\n" + task.getResult();
        }

        bus.publishInbound(new InboundMessage(
                "system",
                "subagent:" + task.getId(),
                task.getOriginChannel() + ":" + task.getOriginChatId(),
                announceContent
        ));
    }

    /**
     * 根据 ID 获取任务
     */
    public SubagentTask getTask(String taskId) {
        return tasks.get(taskId);
    }

    /**
     * 列出所有任务
     */
    public List<SubagentTask> listTasks() {
        return new ArrayList<>(tasks.values());
    }

    /**
     * 获取任务数量
     */
    public int getTaskCount() {
        return tasks.size();
    }

    /**
     * 定期清理过期任务
     */
    private void maybeCleanupOldTasks() {
        long now = System.currentTimeMillis();
        if (now - lastCleanup < CLEANUP_INTERVAL_MS) {
            return;
        }

        lastCleanup = now;
        int removed = 0;

        for (Map.Entry<String, SubagentTask> entry : tasks.entrySet()) {
            SubagentTask task = entry.getValue();
            boolean isFinished = "completed".equals(task.getStatus()) || "failed".equals(task.getStatus());
            boolean isExpired = now - task.getCreated() > TASK_RETENTION_MS;

            if (isFinished && isExpired) {
                tasks.remove(entry.getKey());
                removed++;
            }
        }

        if (removed > 0) {
            log.info("清理过期子代理任务: removed={}, remaining={}", removed, tasks.size());
        }
    }

    /**
     * 关闭线程池
     */
    public void shutdown() {
        log.info("关闭 SubagentManager");
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
