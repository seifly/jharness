package cn.seifly.jharness.plugins.rag.core.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * 文档切分后的一个 Chunk。
 *
 * <p>Chunk 切分优先保证语义完整性（段落 → 标题 → 句子 → 固定长度 + overlap），
 * 不简单使用 substring。每个 Chunk 携带所属文档 ID、序号、内容与元数据。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentChunk {

    /** Chunk 唯一 ID */
    private String id;

    /** 所属文档 ID */
    private String documentId;

    /** 在文档中的顺序（从 0 开始） */
    private int chunkIndex;

    /** Chunk 纯文本内容 */
    private String content;

    /** Chunk 级元数据（继承自文档元数据 + 片段位置等） */
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();
}
