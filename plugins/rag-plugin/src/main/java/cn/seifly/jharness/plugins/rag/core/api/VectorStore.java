package cn.seifly.jharness.plugins.rag.core.api;

import org.pf4j.ExtensionPoint;
import cn.seifly.jharness.plugins.rag.core.model.VectorDocument;
import cn.seifly.jharness.plugins.rag.core.model.VectorSearchResult;

import java.util.List;
import java.util.Map;

/**
 * 向量存储 SPI（核心抽象）。
 *
 * <p><b>设计原则：业务层只依赖本接口，绝不依赖任何具体实现。</b>
 * Redis 只是当前默认实现，未来可无侵入替换为 PgVector / Qdrant / Milvus。
 *
 * <p>本接口继承 PF4J {@link ExtensionPoint}，遵循 JHarness「一切皆插件」理念：
 * 任何向量库实现都可作为插件扩展点被发现与装配，而不破坏宿主插件生命周期。
 *
 * <pre>
 * JHarness RAG Core
 *         │
 *         ▼
 *    VectorStore (SPI)
 *         │
 *    ┌────┼────────┐
 *    ▼    ▼        ▼
 *  Redis PgVector Qdrant
 * </pre>
 */
public interface VectorStore extends ExtensionPoint {

    /**
     * 写入单个向量文档（upsert：存在则覆盖）。
     */
    void upsert(VectorDocument document);

    /**
     * 批量写入（推荐：一次网络往返，性能更优）。
     */
    void upsertBatch(List<VectorDocument> documents);

    /**
     * 向量相似度检索（无过滤条件）。
     *
     * @param embedding 查询向量
     * @param topK      返回 Top-K
     */
    List<VectorSearchResult> search(float[] embedding, int topK);

    /**
     * 向量相似度检索（带 Metadata 过滤）。
     *
     * @param embedding 查询向量
     * @param topK      返回 Top-K
     * @param filter    Metadata 过滤条件（tenantId / knowledgeBaseId ...）
     */
    List<VectorSearchResult> search(float[] embedding, int topK, Map<String, Object> filter);

    /**
     * 删除单个 Chunk（按 chunkId）。
     */
    void delete(String chunkId);

    /**
     * 删除整个文档的全部 Chunk（按 documentId）。
     */
    void deleteByDocumentId(String documentId);

    /**
     * 清空全部向量数据（谨慎调用）。
     */
    void clear();
}
