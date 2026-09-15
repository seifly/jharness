package cn.seifly.jharness.plugins.ui.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话管理 API 控制器
 *
 * 提供会话的增删改查功能，用于 Web 控制台的会话管理。
 *
 * <p>会话存储在本地磁盘 {@code {workspace}/sessions/} 目录下，
 * 每个会话存储为一个 JSON 文件，文件名为会话 key（冒号/斜杠替换为下划线）。
 */
@RestController
@RequestMapping("/api/sessions")
@CrossOrigin(origins = "${jclaw.gateway.cors-origin:*}", allowedHeaders = "*", methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.DELETE, RequestMethod.OPTIONS})
@Slf4j
public class SessionsController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Value("${jclaw.agent.workspace:~/.jclaw/workspace}")
    private String workspacePath;

    private Path getSessionsDir() {
        String expanded = workspacePath.replaceFirst("^~", System.getProperty("user.home"));
        return Paths.get(expanded, "sessions");
    }

    /**
     * 获取所有会话列表
     *
     * @return 会话列表，包含每个会话的 key、messageCount 和 firstMessage
     */
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> getSessions() {
        List<Map<String, Object>> sessions = new ArrayList<>();
        Path sessionsDir = getSessionsDir();

        if (!Files.exists(sessionsDir)) {
            return ResponseEntity.ok(sessions);
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(sessionsDir, "*.json")) {
            for (Path file : stream) {
                String fileName = file.getFileName().toString();
                if (fileName.startsWith(".")) {
                    continue;
                }
                String sessionKey = fileNameToSessionKey(fileName.replace(".json", ""));

                Map<String, Object> session = readSessionFile(file);
                List<Map<String, Object>> messages = getMessages(session);

                String firstMessage = messages.stream()
                        .filter(m -> "user".equals(m.get("role"))
                                && m.get("content") != null
                                && !m.get("content").toString().isBlank())
                        .findFirst()
                        .map(m -> {
                            String content = m.get("content").toString();
                            return content.length() > 15 ? content.substring(0, 15) + "…" : content;
                        })
                        .orElse("");

                Map<String, Object> info = new LinkedHashMap<>();
                info.put("key", sessionKey);
                info.put("messageCount", messages.size());
                info.put("firstMessage", firstMessage);
                sessions.add(info);
            }
        } catch (IOException e) {
            log.error("Failed to list sessions from {}", sessionsDir, e);
        }

        sessions.sort((a, b) -> {
            long timeA = getFileModifiedTime(sessionsDir, a.get("key").toString());
            long timeB = getFileModifiedTime(sessionsDir, b.get("key").toString());
            return Long.compare(timeB, timeA);
        });

        return ResponseEntity.ok(sessions);
    }

    /**
     * 创建新会话
     *
     * @param requestBody 包含 sessionKey 的请求体
     * @return 创建的会话信息
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> createSession(@RequestBody Map<String, String> requestBody) {
        String sessionKey = requestBody.get("sessionKey");

        if (sessionKey == null || sessionKey.isBlank()) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "sessionKey is required");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
        }

        Path sessionsDir = getSessionsDir();
        try {
            Files.createDirectories(sessionsDir);
            Path file = sessionsDir.resolve(sessionKeyToFileName(sessionKey) + ".json");

            Map<String, Object> session = new LinkedHashMap<>();
            session.put("key", sessionKey);
            session.put("messages", new ArrayList<>());
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), session);

            Map<String, Object> result = new HashMap<>();
            result.put("key", sessionKey);
            result.put("messageCount", 0);
            return ResponseEntity.ok(result);
        } catch (IOException e) {
            log.error("Failed to create session: {}", sessionKey, e);
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "Failed to create session");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    /**
     * 获取指定会话的详情
     *
     * @param key 会话 key（URL 编码）
     * @return 会话消息历史
     */
    @GetMapping("/{key}")
    public ResponseEntity<List<Map<String, Object>>> getSessionDetail(@PathVariable("key") String key) {
        String decodedKey = URLDecoder.decode(key, StandardCharsets.UTF_8);
        Path sessionsDir = getSessionsDir();
        Path file = sessionsDir.resolve(sessionKeyToFileName(decodedKey) + ".json");

        if (!Files.exists(file)) {
            return ResponseEntity.ok(new ArrayList<>());
        }

        Map<String, Object> session = readSessionFile(file);
        List<Map<String, Object>> messages = getMessages(session);

        Object summary = session.get("summary");
        List<Map<String, Object>> result = new ArrayList<>();
        if (summary instanceof String s && !s.isBlank()) {
            Map<String, Object> summaryMsg = new HashMap<>();
            summaryMsg.put("role", "summary");
            summaryMsg.put("content", s);
            result.add(summaryMsg);
        }

        for (Map<String, Object> msg : messages) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("role", msg.getOrDefault("role", ""));
            m.put("content", msg.get("content") != null ? msg.get("content") : "");

            if (msg.get("images") != null) {
                m.put("images", msg.get("images"));
            }

            if ("assistant".equals(msg.get("role"))) {
                Object toolCalls = msg.get("toolCallRecords");
                if (toolCalls instanceof List<?> list && !list.isEmpty()) {
                    List<Map<String, Object>> toolCallsArray = new ArrayList<>();
                    for (Object o : list) {
                        if (o instanceof Map<?, ?> record) {
                            Map<String, Object> r = new LinkedHashMap<>();
                            r.put("toolName", record.get("toolName"));
                            r.put("argsSummary", record.get("argsSummary"));
                            r.put("resultSummary", record.get("resultSummary"));
                            r.put("success", record.get("success"));
                            toolCallsArray.add(r);
                        }
                    }
                    if (!toolCallsArray.isEmpty()) {
                        m.put("toolCallRecords", toolCallsArray);
                    }
                }
            }

            result.add(m);
        }

        return ResponseEntity.ok(result);
    }

    /**
     * 删除指定会话
     *
     * @param key 会话 key（URL 编码）
     * @return 删除结果
     */
    @DeleteMapping("/{key}")
    public ResponseEntity<Map<String, Object>> deleteSession(@PathVariable("key") String key) {
        String decodedKey = URLDecoder.decode(key, StandardCharsets.UTF_8);
        Path sessionsDir = getSessionsDir();
        Path file = sessionsDir.resolve(sessionKeyToFileName(decodedKey) + ".json");

        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.error("Failed to delete session: {}", decodedKey, e);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "Session deleted");
        return ResponseEntity.ok(result);
    }

    // ==================== 私有辅助方法 ====================

    private String sessionKeyToFileName(String sessionKey) {
        if (sessionKey == null) {
            return "unknown";
        }
        return sessionKey.replaceAll("[:/\\\\*?\"<>|]", "_");
    }

    private String fileNameToSessionKey(String safeFileName) {
        if (safeFileName == null) {
            return "unknown";
        }
        return safeFileName.replaceFirst("_", ":");
    }

    private Map<String, Object> readSessionFile(Path file) {
        try {
            return MAPPER.readValue(file.toFile(), Map.class);
        } catch (IOException e) {
            log.warn("Failed to read session file: {}", file, e);
            return new LinkedHashMap<>();
        }
    }

    private long getFileModifiedTime(Path sessionsDir, String sessionKey) {
        Path file = sessionsDir.resolve(sessionKeyToFileName(sessionKey) + ".json");
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getMessages(Map<String, Object> session) {
        Object messages = session.get("messages");
        if (messages instanceof List<?> list) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    result.add((Map<String, Object>) m);
                }
            }
            return result;
        }
        return new ArrayList<>();
    }
}
