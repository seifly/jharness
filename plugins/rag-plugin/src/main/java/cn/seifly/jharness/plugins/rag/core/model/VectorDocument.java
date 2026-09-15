package cn.seifly.jharness.plugins.rag.core.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 写入向量库的最小单元。
 *
 * <p>一个 VectorDocument 通常对应一个 DocumentChunk + 其 Embedding。
 * Redis 中以 Hash 存储：content / chunkIndex / metadata / embedding / createdAt 等。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VectorDocument {

    /** Chunk 唯一 ID（Redis key: rag:chunk:{chunkId}） */
    private String chunkId;

    /** 所属文档 ID（Redis key: rag:document:{documentId}） */
    private String documentId;

    /** 文档 / Chunk 内容 */
    private String content;

    /** 在文档中的顺序 */
    private int chunkIndex;

    /** 文件名（冗余存储，便于直接阅读） */
    private String fileName;

    /** 元数据（tenantId / knowledgeBaseId / fileType / source ...） */
    private Map<String, Object> metadata;

    /** Embedding 向量（维度来自 EmbeddingService.dimension()，禁止硬编码） */
    private float[] embedding;

    /** 创建时间戳（epoch millis） */
    private long createdAt;
}
