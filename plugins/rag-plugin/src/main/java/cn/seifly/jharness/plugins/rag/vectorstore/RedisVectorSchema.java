package cn.seifly.jharness.plugins.rag.vectorstore;

/**
 * Redis 向量存储（Redis 8 Vector Set）Key 规范与常量。
 *
 * <p>数据模型（不再依赖 RediSearch / FT.* 索引，改用 Redis 8 原生 Vector Set）：
 * <pre>
 *  rag:chunk:{chunkId}         Hash       Chunk 内容与元数据（content / fileName / chunkIndex /
 *                                         createdAt / metadata JSON / 标签字段），供命中回填与文档管理
 *  rag:vec:chunks              Vector Set 全局向量集；元素名 = chunkId，
 *                                         元素自带 JSON 属性（平铺标签），供 {@code VSIM ... FILTER} 过滤
 *  rag:documents               Set        文档 ID 集合（用于列表 / 删除）
 *  rag:doc:{documentId}        Set        文档 → Chunk ID 集合
 *  rag:document:{documentId}   Hash       文档信息（fileName / fileType / chunkCount / createdAt）
 * </pre>
 *
 * <p>向量检索：{@code VSIM rag:vec:chunks ... WITHSCORES [FILTER]} 在 Redis 内完成
 * 「向量近邻 + 标签等值过滤」，再以命中的 chunkId 回读 Hash 详情；写入 / 删除需 Hash、Set、Vector Set 三处同步。
 *
 * <p>标签属性统一以字符串值存储，过滤语义为字符串等值比较（与 {@code InMemoryVectorStore} 一致）。
 */
public final class RedisVectorSchema {

    /** Chunk Hash 前缀 */
    public static final String CHUNK_PREFIX = "rag:chunk:";

    /** 文档 Hash 前缀 */
    public static final String DOC_PREFIX = "rag:document:";

    /** 文档 ID 集合（用于列表 / 删除） */
    public static final String DOC_INDEX_SET = "rag:documents";

    /** 文档 → Chunk 集合前缀 */
    public static final String DOC_CHUNKS_SET = "rag:doc:";

    /** 全局 Vector Set key：元素名 = chunkId，元素属性 = 平铺标签 JSON */
    public static final String VECTOR_SET_KEY = "rag:vec:chunks";

    private RedisVectorSchema() {
    }
}
