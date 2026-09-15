package cn.seifly.jharness.plugins.cron;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * 定时任务状态追踪类
 * 记录任务的执行状态、下次运行时间、上次运行时间及错误信息
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CronJobState {

    private Long nextRunAtMs;
    private Long lastRunAtMs;
    private String lastStatus;
    private String lastError;

    public CronJobState() {}
}
