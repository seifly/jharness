package cn.seifly.jharness.plugins.cron;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * 定时任务负载类
 * 定义任务执行时的具体内容和目标信息
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CronPayload {

    private String kind;
    private String message;
    private String channel;
    private String to;

    public CronPayload() {}

    public CronPayload(String message, String channel, String to) {
        this.kind = "agent_turn";
        this.message = message;
        this.channel = channel;
        this.to = to;
    }
}
