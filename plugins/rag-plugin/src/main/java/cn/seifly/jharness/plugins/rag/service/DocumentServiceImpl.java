package cn.seifly.jharness.plugins.rag.service;

import cn.seifly.jharness.plugins.rag.core.api.DocumentParser;
import cn.seifly.jharness.plugins.rag.core.api.DocumentRepository;
import cn.seifly.jharness.plugins.rag.core.api.DocumentService;
import cn.seifly.jharness.plugins.rag.core.api.EmbeddingService;
import cn.seifly.jharness.plugins.rag.core.api.TextChunker;
import cn.seifly.jharness.plugins.rag.core.api.VectorStore;
import cn.seifly.jharness.plugins.rag.core.exception.RagErrorCode;
import cn.seifly.jharness.plugins.rag.core.exception.RagException;
import cn.seifly.jharness.plugins.rag.core.model.DocumentChunk;
import cn.seifly.jharness.plugins.rag.core.model.DocumentInfo;
import cn.seifly.jharness.plugins.rag.core.model.DocumentMetadata;
import cn.seifly.jharness.plugins.rag.core.model.VectorDocument;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 文档服务实现：上传 → 解析 → 切分 → Embedding → 落库 的编排。
 *
 * <p>全程只依赖接口（{@link DocumentParser} / {@link TextChunker} / {@link EmbeddingService} /
 * {@link VectorStore} / {@link DocumentRepository}），不感知任何具体实现。
 */
public class DocumentServiceImpl implements DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentServiceImpl.class);

    private final DocumentParser documentParser;
    private final TextChunker textChunker;
    private final EmbeddingService embeddingService;
    private final VectorStore vectorStore;
    private final DocumentRepository documentRepository;

    public DocumentServiceImpl(DocumentParser documentParser, TextChunker textChunker,
                               EmbeddingService embeddingService, VectorStore vectorStore,
                               DocumentRepository documentRepository) {
        this.documentParser = documentParser;
        this.textChunker = textChunker;
        this.embeddingService = embeddingService;
        this.vectorStore = vectorStore;
        this.documentRepository = documentRepository;
    }

    @Override
    public UploadResult upload(String fileName, InputStream inputStream, DocumentMetadata metadata) {
        if (inputStream == null) {
            throw new RagException(RagErrorCode.RAG_FILE_EMPTY);
        }
        String ext = StringUtils.lowerCase(StringUtils.substringAfterLast(fileName, "."));
        if (!documentParser.supports(fileName)) {
            throw new RagException(RagErrorCode.RAG_FILE_UNSUPPORTED, "不支持的文件类型: " + fileName);
        }

        // 1) 解析
        String text;
        try {
            text = documentParser.parse(fileName, inputStream);
        } catch (RagException e) {
            throw e;
        } catch (Exception e) {
            throw new RagException(RagErrorCode.RAG_PARSE_FAILED,
                    "解析失败: " + StringUtils.defaultString(e.getMessage()), e);
        }
        if (StringUtils.isBlank(text)) {
            throw new RagException(RagErrorCode.RAG_PARSE_FAILED, "解析后文本为空");
        }
        log.info("[Document] parse fileName={}, textLength={}", fileName, text.length());

        // 2) 切分
        String documentId = UUID.randomUUID().toString();
        List<DocumentChunk> chunks = textChunker.split(documentId, text);
        if (chunks.isEmpty()) {
            throw new RagException(RagErrorCode.RAG_CHUNK_EMPTY);
        }
        log.info("[Document] chunk fileName={}, chunkCount={}", fileName, chunks.size());

        // 3) Embedding
        List<String> texts = new ArrayList<>(chunks.size());
        for (DocumentChunk c : chunks) {
            texts.add(c.getContent());
        }
        List<float[]> vectors;
        try {
            vectors = embeddingService.embedBatch(texts);
        } catch (RagException e) {
            log.error("[Document] embedBatch fileName={}, texts={}", fileName, texts, e);
            throw e;
        } catch (Exception e) {
            log.error("[Document] embedBatch fileName={}, texts={}", fileName, texts, e);
            throw new RagException(RagErrorCode.RAG_EMBEDDING_FAILED,
                    "Embedding 失败: " + StringUtils.defaultString(e.getMessage()), e);
        }
        int dim = embeddingService.dimension();

        // 4) 组装并落库
        Map<String, Object> metaMap = (metadata != null ? metadata : DocumentMetadata.ofUpload(fileName, ext))
                .toFilterMap();
        List<VectorDocument> docs = new ArrayList<>(chunks.size());
        long now = System.currentTimeMillis();
        for (int i = 0; i < chunks.size(); i++) {
            float[] vec = vectors.get(i);
            if (vec == null || vec.length != dim) {
                throw new RagException(RagErrorCode.RAG_VECTOR_DIMENSION,
                        "第 " + i + " 个 Chunk 向量维度异常，期望 " + dim + "，实际 " + (vec == null ? 0 : vec.length));
            }
            DocumentChunk c = chunks.get(i);
            docs.add(VectorDocument.builder()
                    .chunkId(c.getId())
                    .documentId(documentId)
                    .content(c.getContent())
                    .chunkIndex(c.getChunkIndex())
                    .fileName(fileName)
                    .metadata(metaMap)
                    .embedding(vec)
                    .createdAt(now)
                    .build());
        }
        vectorStore.upsertBatch(docs);
        log.info("[VectorStore] upsert fileName={}, vectors={}", fileName, docs.size());

        // 5) 文档元数据
        documentRepository.saveDocument(DocumentInfo.builder()
                .documentId(documentId)
                .fileName(fileName)
                .fileType(ext)
                .chunkCount(chunks.size())
                .createdAt(now)
                .metadata(metaMap)
                .build());

        return new UploadResult(documentId, fileName, chunks.size(), "SUCCESS");
    }

    @Override
    public List<DocumentSummary> listDocuments() {
        return documentRepository.listDocuments();
    }

    @Override
    public DocumentSummary getDocument(String documentId) {
        DocumentSummary s = documentRepository.getDocument(documentId);
        if (s == null) {
            throw new RagException(RagErrorCode.RAG_DOC_NOT_FOUND, "文档不存在: " + documentId);
        }
        return s;
    }

    @Override
    public List<DocumentChunk> getChunks(String documentId) {
        return documentRepository.getChunks(documentId);
    }

    @Override
    public void delete(String documentId) {
        documentRepository.deleteDocument(documentId);
        log.info("[Document] delete documentId={}", documentId);
    }
}
