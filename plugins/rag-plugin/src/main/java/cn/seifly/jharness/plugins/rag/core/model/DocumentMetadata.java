package cn.seifly.jharness.plugins.rag.core.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * 文档 / Chunk 的元数据。
 *
 * <p>用于未来多租户、多知识库隔离，以及向量检索时的 Metadata Filter。
 * 至少包含：tenantId / knowledgeBaseId / fileName / fileType / source。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentMetadata {

    /** 租户 ID（多租户隔离），默认 default */
    private String tenantId;

    /** 知识库 ID（多知识库隔离），默认 default */
    private String knowledgeBaseId;

    /** 原始文件名 */
    private String fileName;

    /** 文件类型（pdf / docx / txt / md） */
    private String fileType;

    /** 来源（upload / crawl / api ...） */
    private String source;

    /** 扩展元数据（任意 KV） */
    @Builder.Default
    private Map<String, Object> extra = new HashMap<>();

    public static DocumentMetadata ofUpload(String fileName, String fileType) {
        return DocumentMetadata.builder()
                .tenantId("default")
                .knowledgeBaseId("default")
                .fileName(fileName)
                .fileType(fileType)
                .source("upload")
                .build();
    }

    /**
     * 转换为可被向量库过滤的扁平 Map（仅字符串值，便于 Redis Tag / SQL 条件）。
     */
    public Map<String, Object> toFilterMap() {
        Map<String, Object> m = new HashMap<>();
        if (tenantId != null) m.put("tenantId", tenantId);
        if (knowledgeBaseId != null) m.put("knowledgeBaseId", knowledgeBaseId);
        if (fileName != null) m.put("fileName", fileName);
        if (fileType != null) m.put("fileType", fileType);
        if (source != null) m.put("source", source);
        if (extra != null) m.putAll(extra);
        return m;
    }
}
