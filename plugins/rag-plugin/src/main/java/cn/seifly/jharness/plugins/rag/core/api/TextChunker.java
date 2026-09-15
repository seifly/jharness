package cn.seifly.jharness.plugins.rag.core.api;

import org.pf4j.ExtensionPoint;
import cn.seifly.jharness.plugins.rag.core.model.DocumentChunk;

import java.util.List;

/**
 * 文本切分 SPI。
 *
 * <p>优先保证语义完整性：段落 → 标题 → 句子 → 固定长度 + overlap，
 * 避免简单 substring。继承 PF4J {@link ExtensionPoint}。
 */
public interface TextChunker extends ExtensionPoint {

    /**
     * 将纯文本切分为 Chunk 列表。
     *
     * @param documentId 文档 ID（写入每个 Chunk）
     * @param text       待切分文本
     */
    List<DocumentChunk> split(String documentId, String text);
}
