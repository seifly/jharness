package cn.seifly.jharness.plugins.ui.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Token 消耗统计 API 控制器
 *
 * 支持按日期范围查询 token 消耗，返回总量、按模型分组、按日期分组三个维度的数据。
 *
 * <p>直接从本地磁盘 {@code {workspace}/token-usage/YYYY-MM.json} 读取，
 * 每条记录包含 timestamp、provider、model、promptTokens、completionTokens。
 */
@RestController
@RequestMapping("/api/token-stats")
@CrossOrigin(origins = "${jclaw.gateway.cors-origin:*}", allowedHeaders = "*", methods = {RequestMethod.GET, RequestMethod.OPTIONS})
@Slf4j
public class TokenStatsController {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter MONTH_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM");

    @Value("${jclaw.agent.workspace:~/.jclaw/workspace}")
    private String workspacePath;

    /**
     * 查询 Token 消耗统计
     *
     * @param startDate 开始日期（可选）
     * @param endDate 结束日期（可选）
     * @return 统计结果，包含总量、按模型分组、按日期分组
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getTokenStats(
            @RequestParam(value = "startDate", required = false) String startDate,
            @RequestParam(value = "endDate", required = false) String endDate) {

        try {
            String actualEndDate = (endDate != null && !endDate.isBlank())
                    ? endDate
                    : LocalDate.now().format(DATE_FORMATTER);

            String actualStartDate = (startDate != null && !startDate.isBlank())
                    ? startDate
                    : LocalDate.now().minusDays(30).format(DATE_FORMATTER);

            Map<String, Object> result = queryFromDisk(actualStartDate, actualEndDate);
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Token stats API error", e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.status(500).body(error);
        }
    }

    // ==================== 私有辅助方法 ====================

    private Path getTokenUsageDir() {
        String expanded = workspacePath.replaceFirst("^~", System.getProperty("user.home"));
        return Paths.get(expanded, "token-usage");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> queryFromDisk(String startDate, String endDate) {
        LocalDate start = LocalDate.parse(startDate, DATE_FORMATTER);
        LocalDate end = LocalDate.parse(endDate, DATE_FORMATTER);
        Path dir = getTokenUsageDir();

        long startMs = start.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long endMs = end.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();

        Set<String> months = new LinkedHashSet<>();
        LocalDate cursor = start.withDayOfMonth(1);
        while (!cursor.isAfter(end)) {
            months.add(cursor.format(MONTH_FORMATTER));
            cursor = cursor.plusMonths(1);
        }

        long totalPromptTokens = 0;
        long totalCompletionTokens = 0;
        long totalCalls = 0;
        Map<String, long[]> byModel = new LinkedHashMap<>();
        Map<String, long[]> byDate = new LinkedHashMap<>();

        for (String month : months) {
            Path filePath = dir.resolve(month + ".json");
            if (!Files.exists(filePath)) {
                continue;
            }
            try {
                JsonNode records = MAPPER.readTree(filePath.toFile());
                if (records.isArray()) {
                    for (JsonNode node : records) {
                        long ts = node.path("timestamp").asLong();
                        if (ts < startMs || ts >= endMs) {
                            continue;
                        }

                        int promptTokens = node.path("promptTokens").asInt();
                        int completionTokens = node.path("completionTokens").asInt();
                        String provider = node.path("provider").asText("unknown");
                        String model = node.path("model").asText("unknown");

                        totalPromptTokens += promptTokens;
                        totalCompletionTokens += completionTokens;
                        totalCalls++;

                        String modelKey = provider + "::" + model;
                        byModel.computeIfAbsent(modelKey, k -> new long[3]);
                        byModel.get(modelKey)[0] += promptTokens;
                        byModel.get(modelKey)[1] += completionTokens;
                        byModel.get(modelKey)[2]++;

                        String dateKey = LocalDate.ofInstant(
                                Instant.ofEpochMilli(ts), ZoneId.systemDefault()
                        ).format(DATE_FORMATTER);
                        byDate.computeIfAbsent(dateKey, k -> new long[3]);
                        byDate.get(dateKey)[0] += promptTokens;
                        byDate.get(dateKey)[1] += completionTokens;
                        byDate.get(dateKey)[2]++;
                    }
                }
            } catch (IOException e) {
                log.warn("Failed to read token usage file: {}", filePath, e);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("startDate", startDate);
        result.put("endDate", endDate);
        result.put("totalPromptTokens", totalPromptTokens);
        result.put("totalCompletionTokens", totalCompletionTokens);
        result.put("totalTokens", totalPromptTokens + totalCompletionTokens);
        result.put("totalCalls", totalCalls);

        List<Map<String, Object>> byModelArray = new ArrayList<>();
        for (Map.Entry<String, long[]> entry : byModel.entrySet()) {
            String[] parts = entry.getKey().split("::", 2);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("provider", parts.length > 0 ? parts[0] : "unknown");
            m.put("model", parts.length > 1 ? parts[1] : "unknown");
            m.put("promptTokens", entry.getValue()[0]);
            m.put("completionTokens", entry.getValue()[1]);
            m.put("calls", entry.getValue()[2]);
            byModelArray.add(m);
        }
        result.put("byModel", byModelArray);

        List<Map<String, Object>> byDateArray = new ArrayList<>();
        for (Map.Entry<String, long[]> entry : byDate.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", entry.getKey());
            m.put("promptTokens", entry.getValue()[0]);
            m.put("completionTokens", entry.getValue()[1]);
            m.put("calls", entry.getValue()[2]);
            byDateArray.add(m);
        }
        byDateArray.sort(Comparator.comparing(m -> (String) m.get("date")));
        result.put("byDate", byDateArray);

        return result;
    }
}
