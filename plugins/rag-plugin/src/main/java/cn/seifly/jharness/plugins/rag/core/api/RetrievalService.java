package cn.seifly.jharness.plugins.rag.core.api;

import org.pf4j.ExtensionPoint;
import cn.seifly.jharness.plugins.rag.core.model.RagQuery;
import cn.seifly.jharness.plugins.rag.core.model.VectorSearchResult;

import java.util.List;

/**
 * 检索服务 SPI（Retrieval 阶段）。
 *
 * <p>职责：把用户 query 转成 Embedding → 调用 VectorStore 检索 Top-K。
 * 与 Generation（LLM 生成答案）严格分离，便于未来独立替换 / 扩展。
 */
public interface RetrievalService extends ExtensionPoint {

    /**
     * 检索 Top-K 相关 Chunk（无过滤）。
     */
    List<VectorSearchResult> retrieve(String query, int topK);

    /**
     * 检索 Top-K 相关 Chunk（带 Metadata 过滤）。
     */
    List<VectorSearchResult> retrieve(RagQuery ragQuery);
}
