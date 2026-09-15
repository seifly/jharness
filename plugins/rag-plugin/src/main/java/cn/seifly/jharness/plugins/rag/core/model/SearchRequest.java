package cn.seifly.jharness.plugins.rag.core.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 向量检索请求体。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SearchRequest {
    private String query;
    private int topK;
    /** 可选 Metadata 过滤 */
    private Map<String, Object> filter;
    /** 目标知识库（选填）；指定后仅检索该库下的文档 */
    private String knowledgeBaseId;
}
