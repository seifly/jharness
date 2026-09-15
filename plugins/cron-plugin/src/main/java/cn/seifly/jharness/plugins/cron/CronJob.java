package cn.seifly.jharness.plugins.cron;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * 定时任务实体类，表示一个定时任务的完整信息。
 *
 * 核心属性：
 * - id：任务唯一标识符
 * - name：任务名称（用户友好的描述）
 * - enabled：任务启用状态（可暂停任务而不删除）
 * - schedule：调度配置（支持间隔和 cron 表达式）
 * - payload：任务负载（要执行的消息内容）
 * - state：任务运行状态（最后执行时间、下次执行时间等）
 * - createdAtMs：创建时间戳（毫秒）
 * - updatedAtMs：更新时间戳（毫秒）
 * - deleteAfterRun：执行后是否自动删除（一次性任务）
 *
 * 使用场景：
 * - 定期消息发送
 * - 一次性定时任务
 * - 循环执行任务
 *
 * 序列化说明：
 * - 使用 Jackson 进行 JSON 序列化
 * - 空字段不会被序列化（JsonInclude.NON_NULL）
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CronJob {

    private String id;              // 任务唯一标识符
    private String name;            // 任务名称
    private boolean enabled = true; // 任务启用状态（默认启用）
    private CronSchedule schedule;  // 调度配置
    private CronPayload payload;    // 任务负载
    private CronJobState state;     // 任务运行状态
    private long createdAtMs;       // 创建时间戳（毫秒）
    private long updatedAtMs;       // 更新时间戳（毫秒）
    private boolean deleteAfterRun; // 执行后是否自动删除

    /**
     * 构造函数，初始化任务状态。
     */
    public CronJob() {
        this.state = new CronJobState();
    }
}
