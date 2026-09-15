package cn.seifly.jharness.plugins.rag.embedding;

import cn.seifly.jharness.plugins.rag.core.api.EmbeddingService;
import cn.seifly.jharness.plugins.rag.core.exception.RagErrorCode;
import cn.seifly.jharness.plugins.rag.core.exception.RagException;
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
 * Qwen3-Embedding-0.6B Embedding 服务（OpenAI 兼容 {@code /v1/embeddings} HTTP 调用）。
 *
 * <p>本地大模型推理服务（如 vLLM / llama.cpp / Ollama / SGLang）暴露 OpenAI 兼容接口，
 * 本实现通过标准 {@code /v1/embeddings} 调用生成向量，无需在插件内打包 ONNX 原生库，
 * 跨平台零依赖（仅用 JDK 17 内置 {@code java.net.http} + Jackson）。
 *
 * <p><b>与模型解耦</b>：业务层只依赖 {@link EmbeddingService}；切换为 BGE / OpenAI 云端等，
 * 仅需改 {@code embedding.api.*} 配置，业务代码零修改。
 *
 * <h3>配置（application.yml / 主应用配置）</h3>
 * <pre>
 * embedding:
 *   provider: qwen3           # qwen3 | openai
 *   api:
 *     base-url: http://127.0.0.1:9000/v1
 *     model: Qwen3-Embedding-0.6B
 *     dimensions: 1024
 *     key: ""                 # 本地服务留空
 * </pre>
 */
public class Qwen3EmbeddingService implements EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(Qwen3EmbeddingService.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 完整 embeddings 端点（base-url 去掉末尾 / 后追加 /embeddings） */
    private final String embeddingsUrl;
    private final String model;
    private final int dimension;
    private final String apiKey;
    private final HttpClient httpClient;

    public Qwen3EmbeddingService(String baseUrl, String model, int dimension, String apiKey) {
        this.embeddingsUrl = normalizeBaseUrl(baseUrl) + "/embeddings";
        this.model = model;
        this.dimension = dimension;
        this.apiKey = (apiKey == null) ? "" : apiKey;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        log.info("[Embedding] OpenAI 兼容 Embedding 客户端就绪: url={}, model={}, dim={}",
                embeddingsUrl, model, dimension);
    }

    @Override
    public int dimension() {
        return dimension;
    }

    @Override
    public float[] embed(String text) {
        List<float[]> r = embedBatch(List.of(text));
        return r.get(0);
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("model", model);
            // OpenAI 兼容：input 可为字符串或字符串数组；数组按序返回
            body.put("input", texts.size() == 1 ? texts.get(0) : new ArrayList<>(texts));

            HttpRequest.Builder rb = HttpRequest.newBuilder()
                    .uri(URI.create(embeddingsUrl))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body),
                            StandardCharsets.UTF_8));
            if (!apiKey.isBlank()) {
                rb.header("Authorization", "Bearer " + apiKey);
            }

            HttpResponse<String> resp = httpClient.send(rb.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new RagException(RagErrorCode.RAG_EMBEDDING_FAILED,
                        "Embedding API 返回 HTTP " + resp.statusCode() + "："
                                + truncate(resp.body(), 500));
            }

            JsonNode root = MAPPER.readTree(resp.body());
            JsonNode data = root.get("data");
            if (data == null || !data.isArray()) {
                throw new RagException(RagErrorCode.RAG_EMBEDDING_FAILED,
                        "Embedding 响应缺少 data 数组：" + truncate(resp.body(), 500));
            }

            // 按返回顺序还原（与 input 顺序一致）；缺失项用零向量兜底
            Map<Integer, float[]> ordered = new HashMap<>();
            for (JsonNode item : data) {
                int idx = item.has("index") ? item.get("index").asInt() : ordered.size();
                JsonNode emb = item.get("embedding");
                if (emb == null || !emb.isArray()) {
                    continue;
                }
                float[] vec = new float[emb.size()];
                for (int i = 0; i < vec.length; i++) {
                    vec[i] = (float) emb.get(i).asDouble();
                }
                ordered.put(idx, normalize(vec));
            }

            List<float[]> out = new ArrayList<>(texts.size());
            for (int i = 0; i < texts.size(); i++) {
                float[] v = ordered.get(i);
                if (v == null) {
                    if (log.isWarnEnabled()) {
                        log.warn("[Embedding] 第 {} 条文本未返回向量，使用零向量兜底", i);
                    }
                    v = new float[dimension];
                }
                out.add(v);
            }
            log.debug("[Embedding] 生成 {} 条向量, dim={}", out.size(), dimension);
            return out;
        } catch (RagException e) {
            log.error("[Embedding] 调用 Embedding API 失败: ",e);
            throw e;
        } catch (Exception e) {
            log.error("[Embedding] 调用 Embedding API 失败: ",e);
            throw new RagException(RagErrorCode.RAG_EMBEDDING_FAILED,
                    "调用 Embedding API 失败: " + e.getMessage(), e);
        }
    }

    // ----------------------- 内部工具 -----------------------

    /**
     * L2 归一化（cosine 相似度对长度不敏感，但归一化后点积即余弦，便于 Redis COSINE 度量）。
     */
    private float[] normalize(float[] vec) {
        double norm = 0d;
        for (float v : vec) {
            norm += (double) v * v;
        }
        norm = Math.sqrt(norm);
        if (norm > 1e-12) {
            for (int i = 0; i < vec.length; i++) {
                vec[i] = (float) (vec[i] / norm);
            }
        }
        return vec;
    }

    private static String normalizeBaseUrl(String baseUrl) {
        String u = (baseUrl == null || baseUrl.isBlank()) ? "http://127.0.0.1:9000/v1" : baseUrl.trim();
        return u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
