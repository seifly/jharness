package cn.seifly.jharness.plugins.rag.core.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 向量检索的单个命中结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VectorSearchResult {

    /** Chunk 唯一 ID */
    private String chunkId;

    /** 所属文档 ID */
    private String documentId;

    /** 命中内容 */
    private String content;

    /** 文件名 */
    private String fileName;

    /**
     * 相似度分数（已归一化到 [0,1]，越大越相关）。
     * 由向量库返回的距离转换而来（COSINE 距离 d -> score = 1 - d）。
     */
    private float score;

    /** 元数据（含 tenantId / knowledgeBaseId 等，可用于过滤与展示） */
    private Map<String, Object> metadata;
}
