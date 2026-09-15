package cn.seifly.jharness.plugins.rag.core.api;

import org.pf4j.ExtensionPoint;
import cn.seifly.jharness.plugins.rag.core.model.DocumentChunk;
import cn.seifly.jharness.plugins.rag.core.model.DocumentMetadata;

import java.util.List;

/**
 * 文档服务 SPI：上传 → 解析 → 切分 → Embedding → 落库 的编排门面。
 */
public interface DocumentService extends ExtensionPoint {

    /**
     * 文档上传结果。
     */
    record UploadResult(String documentId, String fileName, int chunkCount, String status) {
    }

    /**
     * 文档概要（用于列表 / 详情展示）。
     */
    record DocumentSummary(String documentId, String fileName, String fileType,
                           int chunkCount, long createdAt, String knowledgeBaseId) {
    }

    /**
     * 上传并入库一篇文档。
     *
     * @param fileName    原始文件名
     * @param inputStream 文件输入流
     * @param metadata    初始元数据（tenantId / knowledgeBaseId 等）
     */
    UploadResult upload(String fileName, java.io.InputStream inputStream, DocumentMetadata metadata);

    /**
     * 列出全部已上传文档（概要）。
     */
    List<DocumentSummary> listDocuments();

    /**
     * 获取单个文档概要。
     */
    DocumentSummary getDocument(String documentId);

    /**
     * 获取某文档的全部 Chunk（用于调试 / 预览）。
     */
    List<DocumentChunk> getChunks(String documentId);

    /**
     * 删除文档（连带其全部 Chunk）。
     */
    void delete(String documentId);
}
