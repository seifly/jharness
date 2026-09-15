package cn.seifly.jharness.plugins.tools;

import cn.seifly.jharness.plugin.framework.tools.TokenUsageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Token 消耗数据存储，负责将每次 LLM 调用的 token 使用情况持久化到 JSON 文件。
 *
 * <p>数据按月分片存储，文件路径格式为：{workspace}/token-usage/YYYY-MM.json</p>
 * <p>线程安全，支持并发读写。</p>
 *
 * <p>#2 重构后实现 SDK 的 {@link TokenUsageService} 契约：由 tools-plugin 在
 * {@code Plugin.start()} 时以无参构造创建实例（{@code storageDir == null}，未就绪），
 * 由 workflow 在加载配置后调用 {@link #init} 推入 workspace 路径完成初始化，
 * 之后 {@link #record} 才会真正持久化。沿用 #1 LlmService 的「消费方 push」模型。</p>
 */
@Slf4j
public class TokenUsageStore implements TokenUsageService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter MONTH_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final String SUBDIR = "token-usage";

    private volatile Path storageDir;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    /**
     * 无参构造：创建未就绪实例，{@code storageDir == null}。
     * 由 tools-plugin 在 {@code Plugin.start()} 内调用，随后由 workflow 调用 {@link #init}。
     */
    public TokenUsageStore() {
        this.storageDir = null;
    }

    /**
     * 兼容旧调用方：直接传入 workspace 完成初始化。
     *
     * @param workspace 工作空间根路径
     */
    public TokenUsageStore(String workspace) {
        init(workspace);
    }

    @Override
    public void init(String workspace) {
        if (workspace == null || workspace.isEmpty()) {
            log.warn("TokenUsageStore.init skipped: workspace is empty");
            return;
        }
        synchronized (this) {
            this.storageDir = Paths.get(workspace, SUBDIR);
            ensureDirectoryExists();
        }
        log.info("TokenUsageStore initialized: workspace={}", workspace);
    }

    /**
     * 记录一次 LLM 调用的 token 消耗。
     */
    @Override
    public void record(String provider, String model, int promptTokens, int completionTokens) {
        if (storageDir == null) {
            // 未 init（workflow 尚未推入 workspace），no-op
            return;
        }
        if (promptTokens <= 0 && completionTokens <= 0) {
            return;
        }

        long timestamp = Instant.now().toEpochMilli();
        String month = LocalDate.now().format(MONTH_FORMATTER);
        Path filePath = storageDir.resolve(month + ".json");

        lock.writeLock().lock();
        try {
            ArrayNode records = loadRecords(filePath);
            ObjectNode entry = MAPPER.createObjectNode();
            entry.put("timestamp", timestamp);
            entry.put("provider", provider != null ? provider : "unknown");
            entry.put("model", model != null ? model : "unknown");
            entry.put("promptTokens", promptTokens);
            entry.put("completionTokens", completionTokens);
            entry.put("totalTokens", promptTokens + completionTokens);
            records.add(entry);
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(filePath.toFile(), records);
        } catch (IOException e) {
            log.error("Failed to record token usage: error={}", e.getMessage());
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 查询指定日期范围内的 token 消耗统计。
     */
    public TokenStats query(String startDate, String endDate) {
        if (storageDir == null) {
            return new TokenStats(0, 0, 0, new LinkedHashMap<>(), new LinkedHashMap<>());
        }
        LocalDate start = LocalDate.parse(startDate, DATE_FORMATTER);
        LocalDate end = LocalDate.parse(endDate, DATE_FORMATTER);

        long startMs = start.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long endMs = end.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();

        // 收集需要读取的月份文件
        Set<String> months = new LinkedHashSet<>();
        LocalDate cursor = start.withDayOfMonth(1);
        while (!cursor.isAfter(end)) {
            months.add(cursor.format(MONTH_FORMATTER));
            cursor = cursor.plusMonths(1);
        }

        long totalPromptTokens = 0;
        long totalCompletionTokens = 0;
        long totalCalls = 0;

        // key: "provider::model"
        Map<String, long[]> byModel = new LinkedHashMap<>();
        // key: "yyyy-MM-dd"
        Map<String, long[]> byDate = new LinkedHashMap<>();

        lock.readLock().lock();
        try {
            for (String month : months) {
                Path filePath = storageDir.resolve(month + ".json");
                if (!Files.exists(filePath)) {
                    continue;
                }
                ArrayNode records = loadRecords(filePath);
                for (var node : records) {
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

                    // 按模型聚合：[promptTokens, completionTokens, callCount]
                    String modelKey = provider + "::" + model;
                    byModel.computeIfAbsent(modelKey, k -> new long[3]);
                    byModel.get(modelKey)[0] += promptTokens;
                    byModel.get(modelKey)[1] += completionTokens;
                    byModel.get(modelKey)[2]++;

                    // 按日期聚合
                    String dateKey = LocalDate.ofInstant(
                            Instant.ofEpochMilli(ts), ZoneId.systemDefault()
                    ).format(DATE_FORMATTER);
                    byDate.computeIfAbsent(dateKey, k -> new long[3]);
                    byDate.get(dateKey)[0] += promptTokens;
                    byDate.get(dateKey)[1] += completionTokens;
                    byDate.get(dateKey)[2]++;
                }
            }
        } finally {
            lock.readLock().unlock();
        }

        return new TokenStats(totalPromptTokens, totalCompletionTokens, totalCalls, byModel, byDate);
    }

    // ==================== 内部工具方法 ====================

    private ArrayNode loadRecords(Path filePath) {
        if (!Files.exists(filePath)) {
            return MAPPER.createArrayNode();
        }
        try {
            return (ArrayNode) MAPPER.readTree(filePath.toFile());
        } catch (IOException e) {
            log.warn("Failed to load token usage file, starting fresh: file={}, error={}",
                    filePath, e.getMessage());
            return MAPPER.createArrayNode();
        }
    }

    private void ensureDirectoryExists() {
        File dir = storageDir.toFile();
        if (!dir.exists() && !dir.mkdirs()) {
            log.error("Failed to create token usage directory: path={}", storageDir);
        }
    }

    // ==================== 统计结果数据类 ====================

    /**
     * Token 消耗统计结果。
     */
    public static class TokenStats {
        public final long totalPromptTokens;
        public final long totalCompletionTokens;
        public final long totalCalls;
        /** key: "provider::model", value: [promptTokens, completionTokens, callCount] */
        public final Map<String, long[]> byModel;
        /** key: "yyyy-MM-dd", value: [promptTokens, completionTokens, callCount] */
        public final Map<String, long[]> byDate;

        public TokenStats(long totalPromptTokens, long totalCompletionTokens, long totalCalls,
                          Map<String, long[]> byModel, Map<String, long[]> byDate) {
            this.totalPromptTokens = totalPromptTokens;
            this.totalCompletionTokens = totalCompletionTokens;
            this.totalCalls = totalCalls;
            this.byModel = byModel;
            this.byDate = byDate;
        }
    }
}
