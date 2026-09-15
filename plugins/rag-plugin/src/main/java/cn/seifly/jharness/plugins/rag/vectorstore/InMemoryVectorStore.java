package cn.seifly.jharness.plugins.rag.vectorstore;

import cn.seifly.jharness.plugins.rag.core.api.DocumentRepository;
import cn.seifly.jharness.plugins.rag.core.api.DocumentService;
import cn.seifly.jharness.plugins.rag.core.api.VectorStore;
import cn.seifly.jharness.plugins.rag.core.exception.RagErrorCode;
import cn.seifly.jharness.plugins.rag.core.exception.RagException;
import cn.seifly.jharness.plugins.rag.core.model.DocumentChunk;
import cn.seifly.jharness.plugins.rag.core.model.DocumentInfo;
import cn.seifly.jharness.plugins.rag.core.model.VectorDocument;
import cn.seifly.jharness.plugins.rag.core.model.VectorSearchResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 内存向量存储（无 Redis 依赖）。
 *
 * <p>用途：
 * <ul>
 *   <li>单元测试 / 集成测试（无需启动 Redis）；</li>
 *   <li>本地轻量开发（`rag.vector-store=memory`）；</li>
 *   <li>作为 {@link VectorStore} SPI 的「第四种实现」示例，证明可无侵入替换。</li>
 * </ul>
 * 检索采用线性扫描 + 余弦相似度，仅用于小规模演示。
 */
public class InMemoryVectorStore implements VectorStore, DocumentRepository {

    private final Map<String, VectorDocument> chunks = new ConcurrentHashMap<>();
    private final Map<String, DocumentInfo> docs = new ConcurrentHashMap<>();
    private final Map<String, List<String>> docChunks = new ConcurrentHashMap<>();

    @Override
    public void upsert(VectorDocument document) {
        chunks.put(document.getChunkId(), document);
        docs.computeIfAbsent(document.getDocumentId(), id -> DocumentInfo.builder()
                .documentId(id).fileName(document.getFileName()).chunkCount(0).createdAt(document.getCreatedAt()).build());
        docChunks.computeIfAbsent(document.getDocumentId(), k -> new CopyOnWriteArrayList<>())
                .add(document.getChunkId());
    }

    @Override
    public void upsertBatch(List<VectorDocument> documents) {
        documents.forEach(this::upsert);
    }

    @Override
    public List<VectorSearchResult> search(float[] embedding, int topK) {
        return search(embedding, topK, null);
    }

    @Override
    public List<VectorSearchResult> search(float[] embedding, int topK, Map<String, Object> filter) {
        if (embedding == null || embedding.length == 0) {
            throw new RagException(RagErrorCode.RAG_VECTOR_DIMENSION, "查询向量为空");
        }
        List<VectorSearchResult> results = new ArrayList<>();
        for (VectorDocument d : chunks.values()) {
            if (d.getEmbedding() == null || d.getEmbedding().length != embedding.length) {
                continue;
            }
            if (filter != null && !matchFilter(d.getMetadata(), filter)) {
                continue;
            }
            float score = cosine(embedding, d.getEmbedding());
            VectorSearchResult r = new VectorSearchResult();
            r.setChunkId(d.getChunkId());
            r.setDocumentId(d.getDocumentId());
            r.setContent(d.getContent());
            r.setFileName(d.getFileName());
            r.setScore(score);
            r.setMetadata(d.getMetadata());
            results.add(r);
        }
        results.sort((a, b) -> Float.compare(b.getScore(), a.getScore()));
        return results.subList(0, Math.min(topK, results.size()));
    }

    @Override
    public void delete(String chunkId) {
        VectorDocument d = chunks.remove(chunkId);
        if (d != null && docChunks.containsKey(d.getDocumentId())) {
            docChunks.get(d.getDocumentId()).remove(chunkId);
        }
    }

    @Override
    public void deleteByDocumentId(String documentId) {
        List<String> ids = docChunks.remove(documentId);
        if (ids != null) {
            ids.forEach(chunks::remove);
        }
        docs.remove(documentId);
    }

    @Override
    public void clear() {
        chunks.clear();
        docs.clear();
        docChunks.clear();
    }

    // ----- DocumentRepository -----

    @Override
    public void saveDocument(DocumentInfo info) {
        docs.put(info.getDocumentId(), info);
    }

    @Override
    public List<DocumentService.DocumentSummary> listDocuments() {
        List<DocumentService.DocumentSummary> list = new ArrayList<>();
        for (DocumentInfo info : docs.values()) {
            list.add(toSummary(info));
        }
        return list;
    }

    @Override
    public DocumentService.DocumentSummary getDocument(String documentId) {
        DocumentInfo info = docs.get(documentId);
        return info == null ? null : toSummary(info);
    }

    @Override
    public List<DocumentChunk> getChunks(String documentId) {
        List<String> ids = docChunks.get(documentId);
        if (ids == null) {
            return new ArrayList<>();
        }
        List<DocumentChunk> result = new ArrayList<>();
        for (String id : ids) {
            VectorDocument d = chunks.get(id);
            if (d != null) {
                result.add(DocumentChunk.builder()
                        .id(d.getChunkId())
                        .documentId(d.getDocumentId())
                        .chunkIndex(d.getChunkIndex())
                        .content(d.getContent())
                        .metadata(d.getMetadata())
                        .build());
            }
        }
        result.sort((a, b) -> Integer.compare(a.getChunkIndex(), b.getChunkIndex()));
        return result;
    }

    @Override
    public void deleteDocument(String documentId) {
        deleteByDocumentId(documentId);
    }

    @Override
    public void clearDocuments() {
        clear();
    }

    // ----- 工具 -----

    private DocumentService.DocumentSummary toSummary(DocumentInfo info) {
        return new DocumentService.DocumentSummary(
                info.getDocumentId(),
                info.getFileName(),
                info.getFileType(),
                info.getChunkCount(),
                info.getCreatedAt(),info.getDocumentId());
    }

    private boolean matchFilter(Map<String, Object> metadata, Map<String, Object> filter) {
        if (metadata == null) {
            return false;
        }
        for (Map.Entry<String, Object> e : filter.entrySet()) {
            Object v = metadata.get(e.getKey());
            if (v == null || !String.valueOf(v).equals(String.valueOf(e.getValue()))) {
                return false;
            }
        }
        return true;
    }

    private static float cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            return 0f;
        }
        return (float) (dot / (Math.sqrt(na) * Math.sqrt(nb)));
    }
}
