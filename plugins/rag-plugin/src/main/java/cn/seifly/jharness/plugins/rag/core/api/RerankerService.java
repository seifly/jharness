package cn.seifly.jharness.plugins.rag.core.api;

import org.pf4j.ExtensionPoint;
import cn.seifly.jharness.plugins.rag.core.model.RerankResult;

import java.util.List;

/**
 * 重排序（Rerank）服务 SPI。
 *
 * <p>在向量召回（粗排）之后，用更精确的 cross-encoder / reranker 模型对候选 Chunk 重新打分排序，
 * 把真正相关的顶上来。业务层只依赖本接口；具体实现（Ollama / Cohere / 自部署等）由配置决定，
 * 切换实现时业务代码零修改。
 *
 * <p>典型调用链路：
 * <pre>
 *   List&lt;VectorSearchResult&gt; candidates = vectorStore.search(q, candidateSize);   // 粗排
 *   List&lt;RerankResult&gt; ranked = reranker.rerank(query, candidatesTexts, topK);     // 精排
 *   // 用 ranked[index].score 覆盖候选分数并据此排序 → 取 topK
 * </pre>
 *
 * <p>继承 PF4J {@link ExtensionPoint}，可融入 JHarness 插件扩展体系。
 */
public interface RerankerService extends ExtensionPoint {

    /**
     * 对候选文档按与 query 的相关性重排序。
     *
     * @param query     查询文本
     * @param documents 候选文档文本（按原始顺序，与返回的 {@code index} 对应）
     * @param topN      返回相关性最高的前 N 个（N 会被实现方约束在 {@code [1, documents.size()]}）
     * @return 按相关性降序排列的重排结果（含原始下标与分数）
     */
    List<RerankResult> rerank(String query, List<String> documents, int topN);
}
