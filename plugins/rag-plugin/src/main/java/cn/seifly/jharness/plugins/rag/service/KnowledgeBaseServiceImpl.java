package cn.seifly.jharness.plugins.rag.service;

import cn.seifly.jharness.plugins.rag.core.api.DocumentService;
import cn.seifly.jharness.plugins.rag.core.api.KnowledgeBaseService;
import cn.seifly.jharness.plugins.rag.core.exception.RagErrorCode;
import cn.seifly.jharness.plugins.rag.core.exception.RagException;
import cn.seifly.jharness.plugins.rag.core.model.KnowledgeBase;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisException;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 知识库管理（Redis 实现）。
 *
 * <p>知识库元信息存于 Redis：Hash {@code rag:kb:{id}} + 索引 Set {@code rag:kbs}。
 * 删除知识库时联动删除其下全部文档（按 {@code knowledgeBaseId} 归属），实现库级隔离清理。
 *
 * <p>Redis 连接管理参照 {@code RedisVectorStore}：按需建立、连接失败抛
 * {@link RagErrorCode#RAG_REDIS_CONNECTION}（不阻断插件启动）。
 */
public class KnowledgeBaseServiceImpl implements KnowledgeBaseService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseServiceImpl.class);

    private static final String KB_PREFIX = "rag:kb:";
    private static final String KB_INDEX_SET = "rag:kbs";
    /** 系统预置默认知识库，禁止删除 / 重建 */
    private static final String DEFAULT_KB = "default";

    private final DocumentService documentService;
    private final String host;
    private final int port;

    private RedisClient client;
    private StatefulRedisConnection<String, String> connection;
    private RedisCommands<String, String> redis;
    private volatile boolean initialized;

    public KnowledgeBaseServiceImpl(DocumentService documentService, String host, int port) {
        this.documentService = documentService;
        this.host = host;
        this.port = port;
    }

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
                this.initialized = true;
                log.info("[KB] Redis 连接就绪: {}:{}", host, port);
            } catch (RedisException e) {
                throw new RagException(RagErrorCode.RAG_REDIS_CONNECTION, "连接 Redis 失败: " + e.getMessage(), e);
            }
        }
    }

    @Override
    public List<KnowledgeBase> list() {
        ensureInitialized();
        try {
            Set<String> ids = redis.smembers(KB_INDEX_SET);
            List<KnowledgeBase> list = new ArrayList<>();
            for (String id : ids) {
                KnowledgeBase kb = read(id);
                if (kb != null) {
                    list.add(kb);
                }
            }
            Map<String, Long> counts = countDocsByKb();
            // 系统预置 default 知识库不落注册表，但始终在列表中可见（承载历史文档）
            boolean hasDefault = false;
            for (KnowledgeBase kb : list) {
                if (DEFAULT_KB.equals(kb.getKnowledgeBaseId())) {
                    hasDefault = true;
                } else {
                    kb.setDocCount(counts.getOrDefault(kb.getKnowledgeBaseId(), 0L));
                }
            }
            if (!hasDefault) {
                KnowledgeBase def = new KnowledgeBase();
                def.setKnowledgeBaseId(DEFAULT_KB);
                def.setName("默认知识库");
                def.setDescription("系统预置默认知识库");
                def.setTenantId(DEFAULT_KB);
                def.setCreatedAt(0L);
                def.setDocCount(counts.getOrDefault(DEFAULT_KB, 0L));
                list.add(def);
            }
            list.sort((a, b) -> Long.compare(b.getCreatedAt(), a.getCreatedAt()));
            return list;
        } catch (RedisException e) {
            throw new RagException(RagErrorCode.RAG_REDIS_CONNECTION, "读取知识库列表失败: " + e.getMessage(), e);
        }
    }

    @Override
    public KnowledgeBase get(String id) {
        if (StringUtils.isBlank(id)) {
            throw new RagException(RagErrorCode.RAG_INVALID_PARAM, "知识库ID为空");
        }
        ensureInitialized();
        KnowledgeBase kb = read(id);
        if (kb == null) {
            throw new RagException(RagErrorCode.RAG_DOC_NOT_FOUND, "知识库不存在: " + id);
        }
        return kb;
    }

    @Override
    public KnowledgeBase create(CreateRequest req) {
        if (req == null || StringUtils.isBlank(req.name())) {
            throw new RagException(RagErrorCode.RAG_INVALID_PARAM, "知识库名称不能为空");
        }
        String id = StringUtils.isNotBlank(req.knowledgeBaseId())
                ? req.knowledgeBaseId().trim() : UUID.randomUUID().toString();
        if (!id.matches("[A-Za-z0-9_\\-]+")) {
            throw new RagException(RagErrorCode.RAG_INVALID_PARAM, "知识库ID仅允许字母、数字、下划线、横线");
        }
        if (DEFAULT_KB.equals(id)) {
            throw new RagException(RagErrorCode.RAG_INVALID_PARAM, "default 为系统预置知识库，不可重建");
        }
        ensureInitialized();
        try {
            if (redis.exists(KB_PREFIX + id) > 0) {
                throw new RagException(RagErrorCode.RAG_INVALID_PARAM, "知识库ID已存在: " + id);
            }
            long now = System.currentTimeMillis();
            KnowledgeBase kb = KnowledgeBase.builder()
                    .knowledgeBaseId(id)
                    .name(req.name().trim())
                    .description(StringUtils.defaultString(req.description()))
                    .tenantId(StringUtils.isNotBlank(req.tenantId()) ? req.tenantId().trim() : DEFAULT_KB)
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
            Map<String, String> fields = new HashMap<>();
            fields.put("knowledgeBaseId", kb.getKnowledgeBaseId());
            fields.put("name", kb.getName());
            fields.put("description", kb.getDescription());
            fields.put("tenantId", kb.getTenantId());
            fields.put("createdAt", String.valueOf(kb.getCreatedAt()));
            fields.put("updatedAt", String.valueOf(kb.getUpdatedAt()));
            redis.hset(KB_PREFIX + id, fields);
            redis.sadd(KB_INDEX_SET, id);
            log.info("[KB] 创建知识库: {}", id);
            return kb;
        } catch (RedisException e) {
            throw new RagException(RagErrorCode.RAG_REDIS_CONNECTION, "创建知识库失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void delete(String id) {
        if (StringUtils.isBlank(id)) {
            throw new RagException(RagErrorCode.RAG_INVALID_PARAM, "知识库ID为空");
        }
        if (DEFAULT_KB.equals(id)) {
            throw new RagException(RagErrorCode.RAG_INVALID_PARAM, "default 为系统预置知识库，不可删除");
        }
        ensureInitialized();
        try {
            if (redis.exists(KB_PREFIX + id) == 0) {
                throw new RagException(RagErrorCode.RAG_DOC_NOT_FOUND, "知识库不存在: " + id);
            }
            // 联动清理该库下全部文档
            int removed = 0;
            for (DocumentService.DocumentSummary s : documentService.listDocuments()) {
                if (id.equals(s.knowledgeBaseId())) {
                    try {
                        documentService.delete(s.documentId());
                        removed++;
                    } catch (Exception ex) {
                        log.warn("[KB] 删除知识库 {} 时清理文档 {} 失败: {}", id, s.documentId(), ex.getMessage());
                    }
                }
            }
            redis.del(KB_PREFIX + id);
            redis.srem(KB_INDEX_SET, id);
            log.info("[KB] 删除知识库 {}，清理文档 {} 篇", id, removed);
        } catch (RedisException e) {
            throw new RagException(RagErrorCode.RAG_REDIS_CONNECTION, "删除知识库失败: " + e.getMessage(), e);
        }
    }

    private KnowledgeBase read(String id) {
        Map<String, String> m = redis.hgetall(KB_PREFIX + id);
        if (m == null || m.isEmpty()) {
            return null;
        }
        KnowledgeBase kb = new KnowledgeBase();
        kb.setKnowledgeBaseId(m.getOrDefault("knowledgeBaseId", id));
        kb.setName(m.getOrDefault("name", ""));
        kb.setDescription(m.getOrDefault("description", ""));
        kb.setTenantId(m.getOrDefault("tenantId", DEFAULT_KB));
        kb.setCreatedAt(parseLong(m.get("createdAt"), 0L));
        kb.setUpdatedAt(parseLong(m.get("updatedAt"), 0L));
        return kb;
    }

    /**
     * 统计各知识库文档数（失败仅记日志，不影响列表主流程）。
     */
    private Map<String, Long> countDocsByKb() {
        Map<String, Long> counts = new HashMap<>();
        try {
            for (DocumentService.DocumentSummary s : documentService.listDocuments()) {
                String kb = s.knowledgeBaseId() == null ? DEFAULT_KB : s.knowledgeBaseId();
                counts.put(kb, counts.getOrDefault(kb, 0L) + 1);
            }
        } catch (Exception e) {
            log.warn("[KB] 统计文档数失败（忽略）: {}", e.getMessage());
        }
        return counts;
    }

    private static long parseLong(String s, long def) {
        if (s == null) {
            return def;
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
