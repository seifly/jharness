package cn.seifly.jharness.plugins.rag.rerank;

import cn.seifly.jharness.plugins.rag.core.api.RerankerService;
import cn.seifly.jharness.plugins.rag.core.exception.RagErrorCode;
import cn.seifly.jharness.plugins.rag.core.exception.RagException;
import cn.seifly.jharness.plugins.rag.core.model.RerankResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 重排序（Rerank）HTTP 客户端，对接 <b>OpenAI 兼容的 {@code /v1/rerank}</b> 服务
 * （vLLM / SGLang / llama.cpp / Ollama 兼容 shim 等本地推理服务）。
 *
 * <p>请求体：{@code {"model", "query", "documents": [...], "top_n"}}；
 * 响应体：{@code {"results": [{"index": 0, "relevance_score": 0.95}, ...]}}，按分数降序。
 * 同时兼容 Ollama 原生 {@code /api/rerank} 的 {@code score} 字段（见 {@link #parseScore}）。
 *
 * <p><b>鉴权</b>：若配置了 {@code rerank.api.key}（如 {@code sk-omlx-seifly}），自动附加
 * {@code Authorization: Bearer <key>} 头，适配需要令牌的本地推理网关。
 *
 * <p>URL 规则：由 {@code rerank.api.base-url} 决定最终端点——
 * 填 {@code http://127.0.0.1:9000/v1} → 请求 {@code .../v1/rerank}（vLLM 风格）；
 * 填 {@code http://localhost:11434/api} → 请求 {@code .../api/rerank}（Ollama 原生风格）。
 *
 * <h3>配置（application.yml，以用户实际配置为准）</h3>
 * <pre>
 * rag:
 *   retrieval:
 *     rerank:
 *       enabled: true
 *       candidate-size: 20
 * rerank:
 *   provider: ollama            # ollama | openai | vllm（均走本 OpenAI 兼容客户端）
 *   api:
 *     base-url: http://127.0.0.1:9000/v1
 *     model: Qwen3-Reranker-0.6B-mxfp8
 *     key: "sk-omlx-seifly"
 * </pre>
 */
public class OllamaRerankerService implements RerankerService {

    private static final Logger log = LoggerFactory.getLogger(OllamaRerankerService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 完整 rerank 端点（base-url 去掉末尾 / 后追加 /rerank） */
    private final String rerankUrl;
    private final String model;
    private final String apiKey;
    private final HttpClient httpClient;

    public OllamaRerankerService(String baseUrl, String model, String apiKey) {
        this.rerankUrl = normalizeBaseUrl(baseUrl) + "/rerank";
        this.model = (model == null || model.isBlank()) ? "qwen3-reranker:0.6b" : model;
        this.apiKey = (apiKey == null) ? "" : apiKey;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        log.info("[Rerank] Reranker 客户端就绪: url={}, model={}, auth={}",
                rerankUrl, this.model, this.apiKey.isBlank() ? "无" : "Bearer");
    }

    @Override
    public List<RerankResult> rerank(String query, List<String> documents, int topN) {
        if (documents == null || documents.isEmpty()) {
            return new ArrayList<>();
        }
        int n = (topN <= 0 || topN > documents.size()) ? documents.size() : topN;
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("model", model);
            body.put("query", query == null ? "" : query);
            body.put("documents", documents);
            body.put("top_n", n);

            HttpRequest.Builder rb = HttpRequest.newBuilder()
                    .uri(URI.create(rerankUrl))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json");
            if (!apiKey.isBlank()) {
                rb.header("Authorization", "Bearer " + apiKey);
            }
            rb.POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body),
                    StandardCharsets.UTF_8));

            HttpResponse<String> resp = httpClient.send(rb.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new RagException(RagErrorCode.RAG_RERANK_FAILED,
                        "Rerank API 返回 HTTP " + resp.statusCode() + "：" + truncate(resp.body(), 500));
            }

            JsonNode root = MAPPER.readTree(resp.body());
            JsonNode results = root.get("results");
            if (results == null || !results.isArray()) {
                throw new RagException(RagErrorCode.RAG_RERANK_FAILED,
                        "Rerank 响应缺少 results 数组：" + truncate(resp.body(), 500));
            }

            List<RerankResult> out = new ArrayList<>(results.size());
            for (JsonNode item : results) {
                int index = item.has("index") ? item.get("index").asInt() : -1;
                float score = parseScore(item);
                out.add(RerankResult.builder().index(index).score(score).build());
            }
            log.debug("[Rerank] 重排 {} 个候选 → 返回 {} 条", documents.size(), out.size());
            return out;
        } catch (RagException e) {
            log.error("[Rerank] 调用 Rerank API 失败: ", e);
            throw e;
        } catch (Exception e) {
            log.error("[Rerank] 调用 Rerank API 失败: ", e);
            throw new RagException(RagErrorCode.RAG_RERANK_FAILED,
                    "调用 Rerank API 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 解析相关性分数：优先 vLLM 风格的 {@code relevance_score}，回退 Ollama 原生的 {@code score}。
     */
    private static float parseScore(JsonNode item) {
        if (item.has("relevance_score")) {
            return (float) item.get("relevance_score").asDouble();
        }
        if (item.has("score")) {
            return (float) item.get("score").asDouble();
        }
        return 0f;
    }

    // ----------------------- 内部工具 -----------------------

    private static String normalizeBaseUrl(String baseUrl) {
        String u = (baseUrl == null || baseUrl.isBlank()) ? "http://localhost:11434/api" : baseUrl.trim();
        return u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
