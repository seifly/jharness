package cn.seifly.jharness.plugins.rag.core.api;

import org.pf4j.ExtensionPoint;
import cn.seifly.jharness.plugins.rag.core.model.DocumentChunk;
import cn.seifly.jharness.plugins.rag.core.model.DocumentInfo;

import java.util.List;

/**
 * 文档元数据仓储 SPI（与向量库解耦）。
 *
 * <p>负责文档级元数据的持久化与查询（列表 / 详情 / Chunk 预览 / 删除）。
 * 与 {@link VectorStore} 同由 Redis 实现类统一承载，保证「Redis 仅存在于具体实现」：
 * 业务层只依赖接口，不直接接触 Redis。
 */
public interface DocumentRepository extends ExtensionPoint {

    void saveDocument(DocumentInfo info);

    List<DocumentService.DocumentSummary> listDocuments();

    DocumentService.DocumentSummary getDocument(String documentId);

    List<DocumentChunk> getChunks(String documentId);

    void deleteDocument(String documentId);

    void clearDocuments();
}
