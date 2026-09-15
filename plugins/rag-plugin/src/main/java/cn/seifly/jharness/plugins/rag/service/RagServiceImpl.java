package cn.seifly.jharness.plugins.rag.service;

import cn.seifly.jharness.plugins.rag.core.api.RagService;
import cn.seifly.jharness.plugins.rag.core.api.RetrievalService;
import cn.seifly.jharness.plugins.rag.core.exception.RagErrorCode;
import cn.seifly.jharness.plugins.rag.core.exception.RagException;
import cn.seifly.jharness.plugins.rag.core.model.ContextItem;
import cn.seifly.jharness.plugins.rag.core.model.RagQuery;
import cn.seifly.jharness.plugins.rag.core.model.RagResult;
import cn.seifly.jharness.plugins.rag.core.model.VectorSearchResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * RAG 服务实现（当前阶段：仅 Retrieval，答案生成留待未来 LLM 扩展）。
 *
 * <p>检索与生成解耦：{@link #query} 只召回相关上下文，不调用 LLM。
 * 未来接入 LLM 时，只需在 {@link RagResult#getAnswer()} 填充生成结果，消费方零修改。
 */
public class RagServiceImpl implements RagService {

    private final RetrievalService retrievalService;

    public RagServiceImpl(RetrievalService retrievalService) {
        this.retrievalService = retrievalService;
    }

    @Override
    public RagResult query(String query, int topK) {
        return query(query, topK, null);
    }

    @Override
    public RagResult query(String query, int topK, Map<String, Object> filter) {
        if (query == null || query.isBlank()) {
            throw new RagException(RagErrorCode.RAG_QUERY_EMPTY);
        }
        int k = topK <= 0 ? 5 : topK;
        List<VectorSearchResult> hits = retrievalService.retrieve(new RagQuery(query, k, filter));

        List<ContextItem> contexts = new ArrayList<>(hits.size());
        for (VectorSearchResult h : hits) {
            contexts.add(ContextItem.builder()
                    .content(h.getContent())
                    .score(h.getScore())
                    .documentId(h.getDocumentId())
                    .metadata(h.getMetadata())
                    .build());
        }
        return RagResult.builder()
                .query(query)
                .contexts(contexts)
                .build();
    }
}
