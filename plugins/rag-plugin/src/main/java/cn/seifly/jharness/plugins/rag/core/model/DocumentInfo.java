package cn.seifly.jharness.plugins.rag.core.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 文档详情（GET /api/rag/documents/{id}）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentInfo {
    private String documentId;
    private String fileName;
    private String fileType;
    private int chunkCount;
    private long createdAt;
    private Map<String, Object> metadata;
}
