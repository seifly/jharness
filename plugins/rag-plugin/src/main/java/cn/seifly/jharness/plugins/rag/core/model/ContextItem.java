package cn.seifly.jharness.plugins.rag.core.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * RAG 上下文条目：检索到的单个相关 Chunk 的精简视图。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContextItem {

    /** 命中内容 */
    private String content;

    /** 相似度分数 [0,1] */
    private float score;

    /** 来源文档 ID */
    private String documentId;

    /** 元数据（tenantId / knowledgeBaseId / fileName ...） */
    private Map<String, Object> metadata;
}
