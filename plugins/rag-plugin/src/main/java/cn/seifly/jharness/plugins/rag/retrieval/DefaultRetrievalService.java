package cn.seifly.jharness.plugins.rag.retrieval;

import cn.seifly.jharness.plugins.rag.core.api.EmbeddingService;
import cn.seifly.jharness.plugins.rag.core.api.RetrievalService;
import cn.seifly.jharness.plugins.rag.core.api.RerankerService;
import cn.seifly.jharness.plugins.rag.core.api.VectorStore;
import cn.seifly.jharness.plugins.rag.core.exception.RagErrorCode;
import cn.seifly.jharness.plugins.rag.core.exception.RagException;
import cn.seifly.jharness.plugins.rag.core.model.RagQuery;
import cn.seifly.jharness.plugins.rag.core.model.RerankResult;
import cn.seifly.jharness.plugins.rag.core.model.VectorSearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 默认检索服务：query → Embedding → VectorStore（粗排）→ Reranker（精排，可选）。
 *
 * <p>Retrieval 与 Generation 严格分离：本类只负责「召回 Top-K 相关 Chunk」，
 * 不接触任何 LLM / Prompt 逻辑，便于未来独立替换或扩展。
 *
 * <p>两阶段检索：
 * <ol>
 *   <li><b>粗排（召回）</b>：query Embedding → 向量库相似度检索，放大取 candidateSize 条候选；</li>
 *   <li><b>精排（重排）</b>：若注入 {@link RerankerService}，把 query 与候选文本送入重排模型，
 *       用模型给出的相关性分数覆盖原向量相似度分数并重新排序，最终取 Top-K；
 *       未注入重排器时退化为纯向量召回（行为与旧版一致）。</li>
 * </ol>
 */
public class DefaultRetrievalService implements RetrievalService {

    private static final Logger log = LoggerFactory.getLogger(DefaultRetrievalService.class);

    private final EmbeddingService embeddingService;
    private final VectorStore vectorStore;
    /** 重排器（可空：为空则跳过精排，走纯向量召回） */
    private final RerankerService reranker;
    /** 粗排候选集大小（应 >= topK），供重排器精排后取 Top-K */
    private final int rerankCandidateSize;

    public DefaultRetrievalService(EmbeddingService embeddingService, VectorStore vectorStore,
                                   RerankerService reranker, int rerankCandidateSize) {
        this.embeddingService = embeddingService;
        this.vectorStore = vectorStore;
        this.reranker = reranker;
        this.rerankCandidateSize = Math.max(rerankCandidateSize, 1);
    }

    @Override
    public List<VectorSearchResult> retrieve(String query, int topK) {
        if (query == null || query.isBlank()) {
            throw new RagException(RagErrorCode.RAG_QUERY_EMPTY);
        }
        float[] q = embeddingService.embed(query);
        log.info("[RAG] retrieval query=\"{}\" topK={} rerank={}", abbreviate(query), topK, reranker != null);
        return searchWithRerank(query, q, topK, null);
    }

    @Override
    public List<VectorSearchResult> retrieve(RagQuery ragQuery) {
        if (ragQuery == null || ragQuery.getQuery() == null || ragQuery.getQuery().isBlank()) {
            throw new RagException(RagErrorCode.RAG_QUERY_EMPTY);
        }
        float[] q = embeddingService.embed(ragQuery.getQuery());
        int topK = ragQuery.getTopK() <= 0 ? 5 : ragQuery.getTopK();
        log.info("[RAG] retrieval(query=\"{}\", topK={}, filter={}, rerank={})",
                abbreviate(ragQuery.getQuery()), topK, ragQuery.getFilter(), reranker != null);
        return searchWithRerank(ragQuery.getQuery(), q, topK, ragQuery.getFilter());
    }

    /**
     * 检索主流程：粗排 →（可选）精排。
     */
    private List<VectorSearchResult> searchWithRerank(String query, float[] q, int topK,
                                                      Map<String, Object> filter) {
        if (reranker == null) {
            // 未启用重排：直接向量召回 Top-K（与旧版行为一致）
            return vectorStore.search(q, topK, filter);
        }

        // 1) 粗排：放大候选集（>= topK），供重排器精排
        int candidateSize = Math.max(rerankCandidateSize, topK);
        List<VectorSearchResult> candidates = vectorStore.search(q, candidateSize, filter);
        if (candidates.isEmpty()) {
            return candidates;
        }

        // 2) 精排：用重排模型对候选内容重新打分
        List<String> docs = candidates.stream().map(VectorSearchResult::getContent).toList();
        List<RerankResult> reranked = reranker.rerank(query, docs, topK);

        // 3) 按重排结果重组，并用 rerank 分数覆盖原向量相似度分数
        List<VectorSearchResult> out = new ArrayList<>(reranked.size());
        for (RerankResult r : reranked) {
            int idx = r.getIndex();
            if (idx < 0 || idx >= candidates.size()) {
                log.warn("[RAG] rerank 返回越界 index={}，已跳过", idx);
                continue;
            }
            VectorSearchResult item = candidates.get(idx);
            item.setScore(r.getScore());
            out.add(item);
        }
        log.info("[RAG] 粗排 {} 条 → 重排后返回 {} 条", candidates.size(), out.size());
        return out;
    }

    private String abbreviate(String s) {
        return s.length() > 40 ? s.substring(0, 40) + "..." : s;
    }
}
