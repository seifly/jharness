package cn.seifly.jharness.plugins.cron;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Data;

/**
 * 定时任务调度配置类
 * 支持三种调度方式：一次性（at）、周期性（every）、Cron 表达式（cron）
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CronSchedule {

    public enum ScheduleKind {
        AT("at"),
        EVERY("every"),
        CRON("cron");

        private final String value;

        ScheduleKind(String value) {
            this.value = value;
        }

        @JsonValue
        public String getValue() {
            return value;
        }

        @JsonCreator
        public static ScheduleKind fromValue(String value) {
            for (ScheduleKind kind : values()) {
                if (kind.value.equals(value)) {
                    return kind;
                }
            }
            throw new IllegalArgumentException("Unknown schedule kind: " + value);
        }
    }

    private ScheduleKind kind;
    private Long atMs;
    private Long everyMs;
    private String expr;
    private String tz;

    public CronSchedule() {}

    public CronSchedule(ScheduleKind kind) {
        this.kind = kind;
    }

    // 工厂方法
    public static CronSchedule at(long atMs) {
        CronSchedule s = new CronSchedule(ScheduleKind.AT);
        s.setAtMs(atMs);
        return s;
    }

    public static CronSchedule every(long everyMs) {
        CronSchedule s = new CronSchedule(ScheduleKind.EVERY);
        s.setEveryMs(everyMs);
        return s;
    }

    public static CronSchedule cron(String expr) {
        CronSchedule s = new CronSchedule(ScheduleKind.CRON);
        s.setExpr(expr);
        return s;
    }
}
