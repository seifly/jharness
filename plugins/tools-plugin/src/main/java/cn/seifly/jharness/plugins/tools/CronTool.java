package cn.seifly.jharness.plugins.tools;

import cn.seifly.jharness.plugin.framework.tools.Tool;
import cn.seifly.jharness.plugin.framework.tools.ToolContextAware;
import cn.seifly.jharness.plugin.framework.tools.ToolException;

import cn.seifly.jharness.plugin.framework.bus.MessageBus;
import cn.seifly.jharness.plugins.cron.CronJob;
import cn.seifly.jharness.plugins.cron.CronSchedule;
import cn.seifly.jharness.plugins.cron.CronService;
import cn.seifly.jharness.plugin.framework.StringUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 定时任务工具，调度提醒和任务。
 *
 * 允许 Agent 创建、管理和执行定时任务。
 *
 * 支持的操作：
 * - add：添加新任务
 * - list：列出所有已调度的任务
 * - remove：删除指定任务
 * - enable/disable：启用或禁用任务
 */
@Slf4j
public class CronTool implements Tool, ToolContextAware {

    private static final int MESSAGE_PREVIEW_LENGTH = 30;  // 任务名称预览长度
    private static final String DEFAULT_CHANNEL = "cli";   // 默认通道
    private static final String DEFAULT_CHAT_ID = "direct";// 默认聊天 ID

    private final CronService cronService;   // 定时任务服务
    private final JobExecutor executor;      // 任务执行器
    private final MessageBus msgBus;         // 消息总线

    private String channel = "";             // 当前通道
    private String chatId = "";              // 当前聊天 ID

    /**
     * 通过 Agent 执行任务的接口。
     */
    public interface JobExecutor {
        String processDirectWithChannel(String content, String sessionKey, String channel, String chatId) throws Exception;
    }

    public CronTool(CronService cronService, JobExecutor executor, MessageBus msgBus) {
        this.cronService = cronService;
        this.executor = executor;
        this.msgBus = msgBus;
    }

    @Override
    public String name() {
        return "cron";
    }

    @Override
    public String description() {
       return "调度提醒和任务。重要：当用户要求被提醒或调度时，" +
               "您必须调用此工具。使用 'at_seconds' 进行一次性提醒（例如，'10分钟后提醒我' → at_seconds=600）。" +
               "仅在重复任务时使用 'every_seconds'（例如，'每2小时' → every_seconds=7200）。" +
               "使用 'cron_expr' 进行复杂的重复调度（例如，'0 9 * * *' 表示每天上午9点）。";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> params = new HashMap<>();
        params.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> action = new HashMap<>();
        action.put("type", "string");
        action.put("enum", List.of("add", "list", "remove", "enable", "disable"));
        action.put("description", "要执行的操作。当用户想要调度提醒或任务时使用 'add'。");
        properties.put("action", action);

        Map<String, Object> message = new HashMap<>();
        message.put("type", "string");
        message.put("description", "触发时显示的提醒/任务消息（add 操作必需）");
        properties.put("message", message);

        Map<String, Object> atSeconds = new HashMap<>();
        atSeconds.put("type", "integer");
        atSeconds.put("description", "一次性提醒：从现在起多少秒后触发（例如，600表示10分钟后）。");
        properties.put("at_seconds", atSeconds);

        Map<String, Object> everySeconds = new HashMap<>();
        everySeconds.put("type", "integer");
        everySeconds.put("description", "重复间隔（秒）（例如，3600表示每小时）。");
        properties.put("every_seconds", everySeconds);

        Map<String, Object> cronExpr = new HashMap<>();
        cronExpr.put("type", "string");
        cronExpr.put("description", "用于复杂重复调度的 Cron 表达式（例如，'0 9 * * *' 表示每天上午9点）。");
        properties.put("cron_expr", cronExpr);

        Map<String, Object> jobId = new HashMap<>();
        jobId.put("type", "string");
        jobId.put("description", "任务 ID（用于 remove/enable/disable 操作）");
        properties.put("job_id", jobId);

        params.put("properties", properties);
        params.put("required", new String[]{"action"});

        return params;
    }

    public void setContext(String channel, String chatId) {
        this.channel = channel != null ? channel : "";
        this.chatId = chatId != null ? chatId : "";
    }

    @Override
    public void setChannelContext(String channel, String chatId) {
        setContext(channel, chatId);
    }

    @Override
    public String execute(Map<String, Object> args) throws ToolException {
        String action = (String) args.get("action");
        if (action == null) {
            return "错误: 操作参数是必需的";
        }

        return switch (action) {
            case "add" -> addJob(args);
            case "list" -> listJobs();
            case "remove" -> removeJob(args);
            case "enable" -> enableJob(args, true);
            case "disable" -> enableJob(args, false);
            default -> "错误: 未知操作: " + action;
        };
    }

    private String addJob(Map<String, Object> args) {
        if (channel.isEmpty() || chatId.isEmpty()) {
            return "错误: 无会话上下文（通道/聊天ID未设置）。请在活跃对话中使用此工具。";
        }

        String message = (String) args.get("message");
        if (message == null || message.isEmpty()) {
            return "错误: add 操作需要 message 参数";
        }

        CronSchedule schedule = parseSchedule(args);
        if (schedule == null) {
            return "错误: 必须提供 at_seconds、every_seconds 或 cron_expr 之一";
        }

        String messagePreview = StringUtils.truncate(message, MESSAGE_PREVIEW_LENGTH);
        CronJob job = cronService.addJob(messagePreview, schedule, message, channel, chatId);

        log.info("Added cron job: job_id={}, name={}, kind={}",
                job.getId(), messagePreview, schedule.getKind());

        return "Created job '" + job.getName() + "' (id: " + job.getId() + ")";
    }

    private CronSchedule parseSchedule(Map<String, Object> args) {
        Number atSeconds = (Number) args.get("at_seconds");
        Number everySeconds = (Number) args.get("every_seconds");
        String cronExpr = (String) args.get("cron_expr");

        if (atSeconds != null) {
            long atMs = System.currentTimeMillis() + atSeconds.longValue() * 1000;
            return CronSchedule.at(atMs);
        } else if (everySeconds != null) {
            long everyMs = everySeconds.longValue() * 1000;
            return CronSchedule.every(everyMs);
        } else if (cronExpr != null && !cronExpr.isEmpty()) {
            return CronSchedule.cron(cronExpr);
        }

        return null;
    }

    private String listJobs() {
        List<CronJob> jobs = cronService.listJobs(false);

        if (jobs.isEmpty()) {
            return "No scheduled jobs.";
        }

        StringBuilder result = new StringBuilder("Scheduled jobs:\n");
        for (CronJob j : jobs) {
            String scheduleInfo = formatScheduleInfo(j.getSchedule());
            result.append("- ").append(j.getName())
                  .append(" (id: ").append(j.getId())
                  .append(", ").append(scheduleInfo).append(")\n");
        }

        return result.toString();
    }

    private String formatScheduleInfo(CronSchedule schedule) {
        return switch (schedule.getKind()) {
            case EVERY -> schedule.getEveryMs() != null
                    ? "every " + (schedule.getEveryMs() / 1000) + "s"
                    : "unknown";
            case CRON -> schedule.getExpr();
            case AT -> "one-time";
            default -> "unknown";
        };
    }

    private String removeJob(Map<String, Object> args) {
        String jobId = (String) args.get("job_id");
        if (jobId == null || jobId.isEmpty()) {
            return "Error: job_id is required for remove";
        }

        return cronService.removeJob(jobId)
                ? "Removed job " + jobId
                : "Job " + jobId + " not found";
    }

    private String enableJob(Map<String, Object> args, boolean enable) {
        String jobId = (String) args.get("job_id");
        if (jobId == null || jobId.isEmpty()) {
            return "Error: job_id is required for enable/disable";
        }

        CronJob job = cronService.enableJob(jobId, enable);
        if (job == null) {
            return "Job " + jobId + " not found";
        }

        String status = enable ? "enabled" : "disabled";
        return "Job '" + job.getName() + "' " + status;
    }

    /**
     * 执行定时任务。
     */
    public String executeJob(CronJob job) {
        String jobChannel = getJobChannel(job);
        String jobChatId = getJobChatId(job);
        return executeJobThroughAgent(job, jobChannel, jobChatId);
    }

    private String getJobChannel(CronJob job) {
        String jobChannel = job.getPayload().getChannel();
        return (jobChannel != null && !jobChannel.isEmpty()) ? jobChannel : DEFAULT_CHANNEL;
    }

    private String getJobChatId(CronJob job) {
        String jobChatId = job.getPayload().getTo();
        return (jobChatId != null && !jobChatId.isEmpty()) ? jobChatId : DEFAULT_CHAT_ID;
    }

    private String executeJobThroughAgent(CronJob job, String jobChannel, String jobChatId) {
        String sessionKey = "cron-" + job.getId();

        try {
            executor.processDirectWithChannel(
                    job.getPayload().getMessage(),
                    sessionKey,
                    jobChannel,
                    jobChatId
            );
            return "ok";
        } catch (Exception e) {
            log.error("Failed to execute cron job: job_id={}, error={}",
                    job.getId(), e.getMessage());
            return "Error: " + e.getMessage();
        }
    }
}
