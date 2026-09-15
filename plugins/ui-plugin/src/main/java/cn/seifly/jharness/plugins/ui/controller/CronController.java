package cn.seifly.jharness.plugins.ui.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 定时任务 API 控制器
 *
 * 提供定时任务的增删改查和启停功能。
 *
 * <p>定时任务存储在本地磁盘 {@code {workspace}/cron/jobs.json}，
 * 结构为 {@code CronStore{version, List<CronJob>}}。
 */
@RestController
@RequestMapping("/api/cron")
@CrossOrigin(origins = "${jclaw.gateway.cors-origin:*}", allowedHeaders = "*", methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE, RequestMethod.OPTIONS})
@Slf4j
public class CronController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Value("${jclaw.agent.workspace:~/.jclaw/workspace}")
    private String workspacePath;

    /**
     * 获取所有定时任务列表
     *
     * @return 定时任务列表，包含 id、name、启用状态、计划表达式及下次运行时间
     */
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> listCronJobs() {
        List<Map<String, Object>> jobs = getJobsList();
        return ResponseEntity.ok(jobs);
    }

    /**
     * 创建新定时任务
     *
     * 支持 cron 表达式与固定间隔两种方式。
     * 缺少 schedule 字段时返回 400。
     *
     * @param request 包含 name、message、cron 或 everySeconds、channel、to 的请求体
     * @return 创建结果，包含任务 id
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> createCronJob(@RequestBody Map<String, Object> request) {
        String name = (String) request.getOrDefault("name", "");
        String message = (String) request.getOrDefault("message", "");

        Map<String, Object> schedule;
        if (request.containsKey("cron")) {
            schedule = new LinkedHashMap<>();
            schedule.put("kind", "cron");
            schedule.put("expr", request.get("cron"));
        } else if (request.containsKey("everySeconds")) {
            schedule = new LinkedHashMap<>();
            schedule.put("kind", "every");
            schedule.put("everyMs", ((Number) request.get("everySeconds")).longValue() * 1000);
        } else {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Missing schedule");
            return ResponseEntity.status(400).body(error);
        }

        String channel = request.containsKey("channel") ? (String) request.get("channel") : null;
        String to = request.containsKey("to") ? (String) request.get("to") : null;

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", "agent_turn");
        payload.put("message", message);
        if (channel != null) payload.put("channel", channel);
        if (to != null) payload.put("to", to);

        Map<String, Object> job = new LinkedHashMap<>();
        job.put("id", UUID.randomUUID().toString());
        job.put("name", name);
        job.put("enabled", true);
        job.put("schedule", schedule);
        job.put("payload", payload);
        job.put("createdAtMs", System.currentTimeMillis());
        job.put("updatedAtMs", System.currentTimeMillis());

        List<Map<String, Object>> jobs = getJobsList();
        jobs.add(job);
        saveJobsList(jobs);

        Map<String, Object> result = new HashMap<>();
        result.put("id", job.get("id"));

        return ResponseEntity.ok(result);
    }

    /**
     * 删除指定定时任务
     *
     * @param id 任务 id
     * @return 删除结果
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deleteCronJob(@PathVariable("id") String id) {
        List<Map<String, Object>> jobs = getJobsList();
        boolean removed = jobs.removeIf(j -> id.equals(j.get("id")));

        if (removed) {
            saveJobsList(jobs);
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "Job removed");
            return ResponseEntity.ok(result);
        } else {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Job not found");
            return ResponseEntity.status(404).body(error);
        }
    }

    /**
     * 启用或禁用指定定时任务
     *
     * @param id 任务 id
     * @param request 包含 enabled 字段的请求体
     * @return 更新结果
     */
    @PutMapping("/{id}/enable")
    public ResponseEntity<Map<String, Object>> enableCronJob(
            @PathVariable("id") String id,
            @RequestBody Map<String, Object> request) {

        boolean enabled = Boolean.TRUE.equals(request.getOrDefault("enabled", true));
        List<Map<String, Object>> jobs = getJobsList();

        Map<String, Object> target = null;
        for (Map<String, Object> job : jobs) {
            if (id.equals(job.get("id"))) {
                target = job;
                break;
            }
        }

        if (target != null) {
            target.put("enabled", enabled);
            target.put("updatedAtMs", System.currentTimeMillis());
            saveJobsList(jobs);

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "Job " + (enabled ? "enabled" : "disabled"));
            return ResponseEntity.ok(result);
        } else {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Job not found");
            return ResponseEntity.status(404).body(error);
        }
    }

    // ==================== 私有辅助方法 ====================

    private Path getCronDir() {
        String expanded = workspacePath.replaceFirst("^~", System.getProperty("user.home"));
        return Paths.get(expanded, "cron");
    }

    private Path getJobsFile() {
        return getCronDir().resolve("jobs.json");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getJobsList() {
        Path file = getJobsFile();
        if (!Files.exists(file)) {
            return new ArrayList<>();
        }
        try {
            Map<String, Object> store = MAPPER.readValue(file.toFile(), Map.class);
            Object jobs = store.get("jobs");
            if (jobs instanceof List<?> list) {
                List<Map<String, Object>> result = new ArrayList<>();
                for (Object o : list) {
                    if (o instanceof Map<?, ?> m) {
                        result.add((Map<String, Object>) m);
                    }
                }
                return result;
            }
        } catch (IOException e) {
            log.error("Failed to read cron jobs from {}", file, e);
        }
        return new ArrayList<>();
    }

    private void saveJobsList(List<Map<String, Object>> jobs) {
        Path file = getJobsFile();
        try {
            Files.createDirectories(file.getParent());
            Map<String, Object> store = new LinkedHashMap<>();
            store.put("version", 1);
            store.put("jobs", jobs);
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), store);
        } catch (IOException e) {
            log.error("Failed to save cron jobs to {}", file, e);
        }
    }
}
