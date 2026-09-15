package cn.seifly.jharness.plugins.rag.core.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * RAG 查询请求体。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class QueryRequest {
    private String query;
    private int topK;
    private Map<String, Object> filter;
    /** 目标知识库（选填）；指定后仅检索该库下的文档 */
    private String knowledgeBaseId;
}
