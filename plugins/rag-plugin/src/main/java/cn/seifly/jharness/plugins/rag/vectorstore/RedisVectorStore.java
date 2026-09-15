package cn.seifly.jharness.plugins.rag.vectorstore;

import cn.seifly.jharness.plugins.rag.core.api.DocumentRepository;
import cn.seifly.jharness.plugins.rag.core.api.DocumentService;
import cn.seifly.jharness.plugins.rag.core.api.EmbeddingService;
import cn.seifly.jharness.plugins.rag.core.api.VectorStore;
import cn.seifly.jharness.plugins.rag.core.exception.RagErrorCode;
import cn.seifly.jharness.plugins.rag.core.exception.RagException;
import cn.seifly.jharness.plugins.rag.core.model.DocumentChunk;
import cn.seifly.jharness.plugins.rag.core.model.DocumentInfo;
import cn.seifly.jharness.plugins.rag.core.model.VectorDocument;
import cn.seifly.jharness.plugins.rag.core.model.VectorSearchResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisCommandExecutionException;
import io.lettuce.core.RedisException;
import io.lettuce.core.RedisURI;
import io.lettuce.core.VSimArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Redis 向量存储（默认 {@link VectorStore} 实现，基于 <b>Redis 8 原生 Vector Set + Lettuce</b>）。
 *
 * <p><b>Redis 仅存在于本类</b>：业务层（RagService / DocumentService / RetrievalService）
 * 只依赖 {@link VectorStore} / {@link DocumentRepository} 接口，不感知具体客户端。
 *
 * <p>数据流（旧版 jedis + RediSearch FT.KNN 实现的替代方案）：
 * <ul>
 *   <li><b>写入</b>：Chunk 内容写 Hash {@code rag:chunk:{id}}；Embedding 向量与平铺标签写 Vector Set
 *       {@code rag:vec:chunks}（元素名 = chunkId，元素属性 = 标签 JSON）；</li>
 *   <li><b>检索</b>：{@code VSIM ... WITHSCORES} 取相似度 Top-K，带过滤条件时追加
 *       {@code FILTER}（等价旧 FT Tag 过滤，逐 Chunk 字符串等值比较），命中元素再回填 Hash 详情；</li>
 *   <li><b>删除/清空</b>：Hash、Set、Vector Set 三处同步清理。</li>
 * </ul>
 *
 * <p><b>客户端</b>：{@code io.lettuce:lettuce-core ≥ 6.7.0.RELEASE}（typed {@code RedisVectorSetCommands}，
 * 已并入同步 {@code RedisCommands} 接口）；服务端需 <b>Redis ≥ 8.0</b>（Vector Set 原生支持）。
 * 旧版基于 RediSearch 的 FT 索引（rag_vector_idx）数据不做迁移，需清空后重新上传。
 *
 * <p><b>降级策略</b>：连接按需建立——Redis 未启动 / 版本不支持 Vector Set 均<b>不阻断插件启动</b>；
 * 文档写入与管理照常可用，仅向量检索抛 {@link RagErrorCode#RAG_REDIS_INDEX_MISSING}。
 */
public class RedisVectorStore implements VectorStore, DocumentRepository {

    private static final Logger log = LoggerFactory.getLogger(RedisVectorStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 可作为 FILTER 表达式中属性路径（{@code .field}）的字段名 */
    private static final Pattern ATTR_KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final String host;
    private final int port;
    private final int dimension;

    private RedisClient client;
    private StatefulRedisConnection<String, String> connection;
    private RedisCommands<String, String> redis;

    private volatile boolean initialized;
    /** Redis 服务端是否支持 Vector Set（探测失败自动置 false：写入降级、检索报错） */
    private volatile boolean vectorSetAvailable = true;

    public RedisVectorStore(String host, int port, EmbeddingService embeddingService) {
        this.host = host;
        this.port = port;
        // 向量维度来自 EmbeddingService（禁止硬编码），搜索 / 写向量集均以此为基准
        this.dimension = embeddingService.dimension();
    }

    // ===================== 连接与能力探测 =====================

    /**
     * 按需建立 Lettuce 连接（避免 Redis 未启动导致插件 / Bean 装配失败）。
     */
    private void ensureInitialized() {
        if (initialized) {
            return;
        }
        synchronized (this) {
            if (initialized) {
                return;
            }
            try {
                this.client = RedisClient.create(RedisURI.create(host, port));
                this.connection = client.connect();
                this.redis = connection.sync();
                probeVectorSet();
                this.initialized = true;
                log.info("[VectorStore] Redis 连接就绪: redis={}:{}, dimension={}", host, port, dimension);
            } catch (RedisException e) {
                throw new RagException(RagErrorCode.RAG_REDIS_CONNECTION,
                        "连接 Redis 失败: " + safeMsg(e), e);
            }
        }
    }

    /**
     * 探测服务端是否支持 Vector Set（旧版 Redis 对 {@code VCARD} 返回 unknown command）。
     */
    private void probeVectorSet() {
        try {
            redis.vcard(RedisVectorSchema.VECTOR_SET_KEY);
            vectorSetAvailable = true;
        } catch (RedisCommandExecutionException e) {
            // 典型：Redis < 8.0（无 Vector Set 命令）或 key 类型不符 → 标记不可用，不阻断
            vectorSetAvailable = false;
            log.warn("[VectorStore] Redis 不支持 Vector Set（{}）。文档写入与管理不受影响，"
                    + "向量检索不可用；可升级 Redis 8+ 或将 rag.vector-store 设为 memory。key={}",
                    safeMsg(e), RedisVectorSchema.VECTOR_SET_KEY);
        }
    }

    // ===================== VectorStore =====================

    @Override
    public void upsert(VectorDocument document) {
        ensureInitialized();
        try {
            String key = RedisVectorSchema.CHUNK_PREFIX + document.getChunkId();
            Map<String, String> fields = new HashMap<>();
            fields.put("chunkId", document.getChunkId());
            fields.put("documentId", document.getDocumentId());
            fields.put("content", document.getContent());
            fields.put("fileName", document.getFileName() == null ? "" : document.getFileName());
            fields.put("chunkIndex", String.valueOf(document.getChunkIndex()));
            fields.put("createdAt", String.valueOf(document.getCreatedAt()));
            fields.put("metadata", toJson(document.getMetadata()));
            putTag(fields, "tenantId", document.getMetadata());
            putTag(fields, "knowledgeBaseId", document.getMetadata());
            putTag(fields, "fileType", document.getMetadata());
            putTag(fields, "source", document.getMetadata());
            putTag(fields, "fileName", document.getMetadata());

            redis.hset(key, fields);
            // 文档索引集合 + 文档 → Chunk 集合
            redis.sadd(RedisVectorSchema.DOC_INDEX_SET, document.getDocumentId());
            redis.sadd(RedisVectorSchema.DOC_CHUNKS_SET + document.getDocumentId(), document.getChunkId());
            log.debug("[VectorStore] upsert chunk={}, document={}", document.getChunkId(), document.getDocumentId());
        } catch (RedisException e) {
            throw fail("写入文档", e);
        }
        // 向量 + 标签属性写入 Vector Set（故障独立降级，不影响文档存储）
        addToVectorSet(document);
    }

    @Override
    public void upsertBatch(List<VectorDocument> documents) {
        for (VectorDocument d : documents) {
            upsert(d);
        }
    }

    @Override
    public List<VectorSearchResult> search(float[] embedding, int topK) {
        return search(embedding, topK, null);
    }

    @Override
    public List<VectorSearchResult> search(float[] embedding, int topK, Map<String, Object> filter) {
        if (embedding == null || embedding.length != dimension) {
            throw new RagException(RagErrorCode.RAG_VECTOR_DIMENSION,
                    "向量维度不匹配，期望 " + dimension + "，实际 " + (embedding == null ? 0 : embedding.length));
        }
        ensureInitialized();
        if (!vectorSetAvailable) {
            throw new RagException(RagErrorCode.RAG_REDIS_INDEX_MISSING,
                    "Redis 向量索引不可用（VSIM 需要 Redis 8+ Vector Set）："
                            + "请升级 Redis 8+，或将 rag.vector-store 设为 memory 改用内存向量库");
        }
        try {
            Double[] query = toBoxed(embedding);
            FilterPlan plan = buildFilterPlan(filter);
            if (plan.fallback) {
                // 过滤条件无法用 VSIM FILTER 表达（含引号/反斜杠等）→ 全量线性扫描后 Java 端精确过滤
                return searchByLinearScan(query, topK, filter);
            }
            VSimArgs args = plan.expression == null
                    ? VSimArgs.Builder.count((long) topK).explorationFactor(200L)
                    : VSimArgs.Builder.count((long) topK).explorationFactor(200L).filter(plan.expression);

            @SuppressWarnings("unchecked")
            Map<String, Double> hits = redis.vsimWithScore(RedisVectorSchema.VECTOR_SET_KEY, args, query);
            List<VectorSearchResult> out = new ArrayList<>();
            for (Map.Entry<String, Double> entry : sortByScoreDesc(hits)) {
                Map<String, String> chunkFields = redis.hgetall(RedisVectorSchema.CHUNK_PREFIX + entry.getKey());
                if (chunkFields == null || chunkFields.isEmpty()) {
                    continue; // 元素已删的竞态兜底
                }
                out.add(buildResult(chunkFields, entry.getValue()));
            }
            log.info("[VectorStore] search topK={}, filter={}, hits={}", topK, plan.expression, out.size());
            return out;
        } catch (RedisException e) {
            throw toVectorSearchError("向量检索(VSIM)", e);
        }
    }

    @Override
    public void delete(String chunkId) {
        ensureInitialized();
        try {
            String key = RedisVectorSchema.CHUNK_PREFIX + chunkId;
            String docId = redis.hget(key, "documentId");
            redis.del(key);
            if (docId != null && !docId.isEmpty()) {
                redis.srem(RedisVectorSchema.DOC_CHUNKS_SET + docId, chunkId);
            }
        } catch (RedisException e) {
            throw fail("删除 Chunk", e);
        }
        removeFromVectorSet(chunkId);
    }

    @Override
    public void deleteByDocumentId(String documentId) {
        ensureInitialized();
        Set<String> chunks = Collections.emptySet();
        try {
            chunks = redis.smembers(RedisVectorSchema.DOC_CHUNKS_SET + documentId);
            for (String c : chunks) {
                redis.del(RedisVectorSchema.CHUNK_PREFIX + c);
            }
            redis.del(RedisVectorSchema.DOC_CHUNKS_SET + documentId);
            redis.del(RedisVectorSchema.DOC_PREFIX + documentId);
            redis.srem(RedisVectorSchema.DOC_INDEX_SET, documentId);
        } catch (RedisException e) {
            throw fail("删除文档", e);
        }
        for (String c : chunks) {
            removeFromVectorSet(c);
        }
    }

    @Override
    public void clear() {
        ensureInitialized();
        try {
            // 向量集整体重建：删除 key，后续首次 VADD 会以当前维度重新创建
            redis.del(RedisVectorSchema.VECTOR_SET_KEY);
            Set<String> docs = redis.smembers(RedisVectorSchema.DOC_INDEX_SET);
            for (String d : docs) {
                deleteByDocumentId(d);
            }
            redis.del(RedisVectorSchema.DOC_INDEX_SET);
            log.info("[VectorStore] clear 完成");
        } catch (RedisException e) {
            throw fail("清空向量库", e);
        }
    }

    // ===================== DocumentRepository =====================

    @Override
    public void saveDocument(DocumentInfo info) {
        ensureInitialized();
        try {
            Map<String, String> fields = new HashMap<>();
            fields.put("fileName", info.getFileName());
            fields.put("fileType", info.getFileType());
            fields.put("chunkCount", String.valueOf(info.getChunkCount()));
            fields.put("createdAt", String.valueOf(info.getCreatedAt()));
            fields.put("metadata", toJson(info.getMetadata()));
            redis.hset(RedisVectorSchema.DOC_PREFIX + info.getDocumentId(), fields);
            redis.sadd(RedisVectorSchema.DOC_INDEX_SET, info.getDocumentId());
        } catch (RedisException e) {
            throw fail("保存文档元数据", e);
        }
    }

    @Override
    public List<DocumentService.DocumentSummary> listDocuments() {
        ensureInitialized();
        try {
            List<DocumentService.DocumentSummary> list = new ArrayList<>();
            for (String id : redis.smembers(RedisVectorSchema.DOC_INDEX_SET)) {
                DocumentService.DocumentSummary s = getDocument(id);
                if (s != null) {
                    list.add(s);
                }
            }
            return list;
        } catch (RedisException e) {
            throw fail("文档列表", e);
        }
    }

    @Override
    public DocumentService.DocumentSummary getDocument(String documentId) {
        ensureInitialized();
        try {
            Map<String, String> m = redis.hgetall(RedisVectorSchema.DOC_PREFIX + documentId);
            if (m == null || m.isEmpty()) {
                return null;
            }
            return new DocumentService.DocumentSummary(
                    documentId,
                    m.getOrDefault("fileName", ""),
                    m.getOrDefault("fileType", ""),
                    parseInt(m.get("chunkCount"), 0),
                    parseLong(m.get("createdAt"), 0L),
                    parseKnowledgeBaseId(m.get("metadata")));
        } catch (RedisException e) {
            throw fail("文档详情", e);
        }
    }

    @Override
    public List<DocumentChunk> getChunks(String documentId) {
        ensureInitialized();
        try {
            List<DocumentChunk> chunks = new ArrayList<>();
            for (String chunkId : redis.smembers(RedisVectorSchema.DOC_CHUNKS_SET + documentId)) {
                Map<String, String> m = redis.hgetall(RedisVectorSchema.CHUNK_PREFIX + chunkId);
                if (m == null || m.isEmpty()) {
                    continue;
                }
                DocumentChunk c = DocumentChunk.builder()
                        .id(chunkId)
                        .documentId(documentId)
                        .chunkIndex(parseInt(m.get("chunkIndex"), 0))
                        .content(m.getOrDefault("content", ""))
                        .metadata(parseMetadata(m.get("metadata")))
                        .build();
                chunks.add(c);
            }
            chunks.sort(Comparator.comparingInt(DocumentChunk::getChunkIndex));
            return chunks;
        } catch (RedisException e) {
            throw fail("读取 Chunk 列表", e);
        }
    }

    @Override
    public void deleteDocument(String documentId) {
        deleteByDocumentId(documentId);
    }

    @Override
    public void clearDocuments() {
        clear();
    }

    // ===================== Vector Set 辅助 =====================

    /**
     * 将向量与标签属性写入 Vector Set。Vector Set 能力缺失时静默降级（不影响文档存储）。
     */
    private void addToVectorSet(VectorDocument document) {
        if (!vectorSetAvailable) {
            return;
        }
        try {
            redis.vadd(RedisVectorSchema.VECTOR_SET_KEY, document.getChunkId(), toBoxed(document.getEmbedding()));
            redis.vsetattr(RedisVectorSchema.VECTOR_SET_KEY, document.getChunkId(), buildAttrJson(document.getMetadata()));
        } catch (RedisCommandExecutionException e) {
            if (isUnknownCommand(e)) {
                vectorSetAvailable = false;
                log.warn("[VectorStore] Redis 不支持 Vector Set，已降级为仅文档存储: {}", safeMsg(e));
            } else {
                throw new RagException(RagErrorCode.RAG_REDIS_CONNECTION,
                        "写入 Vector Set 失败: " + safeMsg(e), e);
            }
        }
    }

    private void removeFromVectorSet(String chunkId) {
        if (!vectorSetAvailable) {
            return;
        }
        try {
            redis.vrem(RedisVectorSchema.VECTOR_SET_KEY, chunkId);
        } catch (RedisCommandExecutionException e) {
            if (isUnknownCommand(e)) {
                vectorSetAvailable = false;
                log.warn("[VectorStore] Redis 不支持 Vector Set，已降级为仅文档存储: {}", safeMsg(e));
            } else {
                throw new RagException(RagErrorCode.RAG_REDIS_CONNECTION,
                        "从 Vector Set 删除失败: " + safeMsg(e), e);
            }
        }
    }

    /**
     * 兜底检索：以 {@code TRUTH}（线性精确扫描）取回全集，Java 端做等值过滤（语义与 InMemory 一致）。
     * 仅当过滤值含 FILTER 表达式无法安全表达的字符（引号 / 反斜杠 / 控制符）时使用。
     */
    private List<VectorSearchResult> searchByLinearScan(Double[] query, int topK, Map<String, Object> filter) {
        long card = redis.vcard(RedisVectorSchema.VECTOR_SET_KEY);
        if (card <= 0) {
            return new ArrayList<>();
        }
        Map<String, Double> all = redis.vsimWithScore(RedisVectorSchema.VECTOR_SET_KEY,
                VSimArgs.Builder.count(card).truth(), query);
        List<VectorSearchResult> out = new ArrayList<>();
        for (Map.Entry<String, Double> entry : sortByScoreDesc(all)) {
            Map<String, String> chunkFields = redis.hgetall(RedisVectorSchema.CHUNK_PREFIX + entry.getKey());
            if (chunkFields == null || chunkFields.isEmpty()) {
                continue;
            }
            if (!matchFilter(parseMetadata(chunkFields.get("metadata")), filter)) {
                continue;
            }
            out.add(buildResult(chunkFields, entry.getValue()));
            if (out.size() >= topK) {
                break;
            }
        }
        return out;
    }

    // ===================== 内部工具 =====================

    /**
     * 构造 VSIM FILTER 表达式；若过滤条件无法安全表达，则标记走 Java 端兜底扫描。
     *
     * <p>规则：仅处理顶层标量（String/Number/Boolean/Character），字符串化后按等值比较，
     * 与 {@code InMemoryVectorStore#matchFilter} 语义一致。
     */
    private FilterPlan buildFilterPlan(Map<String, Object> filter) {
        if (filter == null || filter.isEmpty()) {
            return FilterPlan.none();
        }
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Object> e : filter.entrySet()) {
            String key = e.getKey();
            Object value = e.getValue();
            if (key == null || value == null || !isScalar(value)) {
                return FilterPlan.fallback();
            }
            if (!ATTR_KEY.matcher(key).matches()) {
                return FilterPlan.fallback();
            }
            String literal = String.valueOf(value);
            if (!isLiteralSafe(literal)) {
                return FilterPlan.fallback();
            }
            parts.add("." + key + " == " + quoteJson(literal));
        }
        if (parts.isEmpty()) {
            return FilterPlan.none();
        }
        return FilterPlan.filter(String.join(" && ", parts));
    }

    /** FILTER 字面量安全字符：除引号 / 反斜杠 / 控制字符外均可（UTF-8 等均可） */
    private static boolean isLiteralSafe(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\' || c < 0x20 || c == 0x7f) {
                return false;
            }
        }
        return true;
    }

    private static boolean isScalar(Object v) {
        return v instanceof String || v instanceof Number || v instanceof Boolean || v instanceof Character;
    }

    /**
     * 构建 Vector Set 元素的 JSON 属性（平铺标签，值统一字符串化），供 VSIM FILTER 使用。
     */
    private String buildAttrJson(Map<String, Object> metadata) {
        Map<String, String> flat = new LinkedHashMap<>();
        if (metadata != null) {
            for (Map.Entry<String, Object> e : metadata.entrySet()) {
                Object val = e.getValue();
                if (e.getKey() == null || val == null || !isScalar(val)) {
                    continue; // 嵌套对象 / null 不做属性过滤
                }
                flat.put(e.getKey(), String.valueOf(val));
            }
        }
        return toJson(flat);
    }

    private boolean matchFilter(Map<String, Object> metadata, Map<String, Object> filter) {
        if (metadata == null) {
            return false;
        }
        for (Map.Entry<String, Object> e : filter.entrySet()) {
            Object v = metadata.get(e.getKey());
            if (v == null || !String.valueOf(v).equals(String.valueOf(e.getValue()))) {
                return false;
            }
        }
        return true;
    }

    private VectorSearchResult buildResult(Map<String, String> chunkFields, double score) {
        VectorSearchResult r = new VectorSearchResult();
        r.setChunkId(chunkFields.get("chunkId"));
        r.setDocumentId(chunkFields.get("documentId"));
        r.setContent(chunkFields.getOrDefault("content", ""));
        r.setFileName(chunkFields.getOrDefault("fileName", ""));
        // Redis Vector Set 的相似度天然在 [0,1]（越大越相似），直接使用
        r.setScore((float) score);
        r.setMetadata(parseMetadata(chunkFields.get("metadata")));
        return r;
    }

    private List<Map.Entry<String, Double>> sortByScoreDesc(Map<String, Double> map) {
        List<Map.Entry<String, Double>> list = new ArrayList<>(map.entrySet());
        list.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        return list;
    }

    private static Double[] toBoxed(float[] vec) {
        Double[] boxed = new Double[vec.length];
        for (int i = 0; i < vec.length; i++) {
            boxed[i] = (double) vec[i];
        }
        return boxed;
    }

    private void putTag(Map<String, String> fields, String key, Map<String, Object> metadata) {
        if (metadata != null && metadata.containsKey(key) && metadata.get(key) != null) {
            fields.put(key, String.valueOf(metadata.get(key)));
        }
    }

    private String toJson(Object obj) {
        try {
            return MAPPER.writeValueAsString(obj == null ? Map.of() : obj);
        } catch (Exception e) {
            return "{}";
        }
    }

    /** 将任意字符串包装为 JSON 字符串字面量（含引号），供 FILTER 表达式使用 */
    private String quoteJson(String value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return "\"\"";
        }
    }

    private Map<String, Object> parseMetadata(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private static boolean isUnknownCommand(RedisException e) {
        return safeMsg(e).toLowerCase(Locale.ROOT).contains("unknown command");
    }

    private RagException toVectorSearchError(String action, RedisException e) {
        if (isUnknownCommand(e)) {
            vectorSetAvailable = false;
            return new RagException(RagErrorCode.RAG_REDIS_INDEX_MISSING,
                    "Redis 向量索引不可用（VSIM 需要 Redis 8+ Vector Set）：" + safeMsg(e));
        }
        return fail(action, e);
    }

    private RagException fail(String action, RedisException e) {
        return new RagException(RagErrorCode.RAG_REDIS_CONNECTION,
                action + " 失败: " + safeMsg(e), e);
    }

    private static String safeMsg(RedisException e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private int parseInt(String s, int def) {
        try {
            return s == null ? def : Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /**
     * 从文档 metadata JSON 中解析 knowledgeBaseId（无则归 default），用于文档归属统计。
     */
    private String parseKnowledgeBaseId(String metaJson) {
        if (metaJson == null || metaJson.isEmpty()) {
            return "default";
        }
        try {
            Map<String, Object> meta = MAPPER.readValue(metaJson, new TypeReference<Map<String, Object>>() {});
            Object v = meta.get("knowledgeBaseId");
            return v != null ? String.valueOf(v) : "default";
        } catch (Exception e) {
            return "default";
        }
    }

    private long parseLong(String s, long def) {
        try {
            return s == null ? def : Long.parseLong(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    // ===================== 内部结构 =====================

    /** VSIM 过滤方案：expression=null 表示不过滤；fallback=true 表示表达式不可用需 Java 端过滤 */
    private static final class FilterPlan {
        private final String expression;
        private final boolean fallback;

        private FilterPlan(String expression, boolean fallback) {
            this.expression = expression;
            this.fallback = fallback;
        }

        static FilterPlan none() {
            return new FilterPlan(null, false);
        }

        static FilterPlan filter(String expression) {
            return new FilterPlan(expression, false);
        }

        static FilterPlan fallback() {
            return new FilterPlan(null, true);
        }
    }
}
