package cn.seifly.jharness.plugins.rag.controller;

import cn.seifly.jharness.plugins.rag.core.api.DocumentService;
import cn.seifly.jharness.plugins.rag.core.api.RagService;
import cn.seifly.jharness.plugins.rag.core.api.RetrievalService;
import cn.seifly.jharness.plugins.rag.core.exception.RagErrorCode;
import cn.seifly.jharness.plugins.rag.core.exception.RagException;
import cn.seifly.jharness.plugins.rag.core.model.*;
import cn.seifly.jharness.plugins.rag.core.model.DocumentMetadata;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG REST 控制器。
 *
 * <p><b>注意</b>：本控制器由插件私有 Spring 子上下文完成依赖注入后，
 * 在 {@code RagPlugin.start()} 中通过宿主 {@code PluginControllerRegistry} 手动挂载到主应用 MVC。
 * 因此本类<b>不使用任何 {@code @Autowired} 字段</b>（宿主 autowireBean 不会注入插件内部 Bean），
 * 依赖全部通过构造函数传入。
 */
@RestController
@RequestMapping("/api/rag")
@Slf4j
public class RagController {

    private final DocumentService documentService;
    private final RagService ragService;
    private final RetrievalService retrievalService;

    // 仅构造函数注入，无 @Autowired 字段（避免宿主 autowireBean 注入失败）
    public RagController(DocumentService documentService, RagService ragService,
                         RetrievalService retrievalService) {
        this.documentService = documentService;
        this.ragService = ragService;
        this.retrievalService = retrievalService;
    }

    /**
     * 上传文档：解析 → 切分 → Embedding → 落库。
     */
    @PostMapping("/documents/upload")
    public RagResponse<UploadResponse> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "tenantId", required = false, defaultValue = "default") String tenantId,
            @RequestParam(value = "knowledgeBaseId", required = false, defaultValue = "default") String knowledgeBaseId) {
        if (file == null || file.isEmpty()) {
            throw new RagException(RagErrorCode.RAG_FILE_EMPTY);
        }
        DocumentMetadata metadata = DocumentMetadata.builder()
                .tenantId(tenantId)
                .knowledgeBaseId(knowledgeBaseId)
                .fileName(file.getOriginalFilename())
                .fileType(StringUtils.lowerCase(StringUtils.substringAfterLast(file.getOriginalFilename(), ".")))
                .source("upload")
                .build();
        DocumentService.UploadResult result = documentService.upload(
                file.getOriginalFilename(), toStream(file), metadata);
        return RagResponse.ok(UploadResponse.builder()
                .documentId(result.documentId())
                .fileName(result.fileName())
                .chunkCount(result.chunkCount())
                .status(result.status())
                .build());
    }

    /**
     * 文档列表。
     */
    @GetMapping("/documents")
    public RagResponse<List<DocumentService.DocumentSummary>> listDocuments() {
        return RagResponse.ok(documentService.listDocuments());
    }

    /**
     * 文档详情。
     */
    @GetMapping("/documents/{id}")
    public RagResponse<DocumentInfo> getDocument(@PathVariable("id") String id) {
        DocumentService.DocumentSummary s = documentService.getDocument(id);
        DocumentInfo info = DocumentInfo.builder()
                .documentId(s.documentId())
                .fileName(s.fileName())
                .fileType(s.fileType())
                .chunkCount(s.chunkCount())
                .createdAt(s.createdAt())
                .build();
        return RagResponse.ok(info);
    }

    /**
     * 删除文档（连带其全部 Chunk）。
     */
    @DeleteMapping("/documents/{id}")
    public RagResponse<String> deleteDocument(@PathVariable("id") String id) {
        documentService.delete(id);
        return RagResponse.ok("deleted:" + id);
    }

    /**
     * 向量检索。
     */
    @PostMapping("/search")
    public RagResponse<SearchResponse> search(@RequestBody SearchRequest request) {
        if (request == null || StringUtils.isBlank(request.getQuery())) {
            throw new RagException(RagErrorCode.RAG_QUERY_EMPTY);
        }
        int topK = request.getTopK() <= 0 ? 5 : request.getTopK();
        Map<String, Object> filter = mergeKbFilter(request.getFilter(), request.getKnowledgeBaseId());
        List<VectorSearchResult> hits = retrievalService.retrieve(
                new RagQuery(request.getQuery(), topK, filter));
        return RagResponse.ok(toSearchResponse(request.getQuery(), topK, hits));
    }

    /**
     * RAG 查询（当前返回检索上下文）。
     */
    @PostMapping("/query")
    public RagResponse<QueryResponse> query(@RequestBody QueryRequest request) {
        if (request == null || StringUtils.isBlank(request.getQuery())) {
            throw new RagException(RagErrorCode.RAG_QUERY_EMPTY);
        }
        int topK = request.getTopK() <= 0 ? 5 : request.getTopK();
        Map<String, Object> filter = mergeKbFilter(request.getFilter(), request.getKnowledgeBaseId());
        RagResult result = new RagResult();
        try {
            result =
                    ragService.query(request.getQuery(), topK, filter);
        } catch (Exception e) {
            log.error("[RagController] query error", e);
            throw new RagException(RagErrorCode.RAG_QUERY_ERROR, e.getMessage(), e);
        }
        QueryResponse resp = QueryResponse.builder()
                .query(result.getQuery())
                .topK(topK)
                .contexts(result.getContexts())
                .build();
        return RagResponse.ok(resp);
    }

    /**
     * 读取插件 application.yml 配置（解析为结构化 Map，用于系统设置页展示）。
     */
    @GetMapping("/config")
    public RagResponse<Map<String, Object>> getConfig() {
        try (InputStream in = new ClassPathResource("application.yml", getClass().getClassLoader()).getInputStream()) {
            Map<String, Object> parsed = new Yaml().load(in);
            return RagResponse.ok(parsed);
        } catch (IOException e) {
            throw new RagException(RagErrorCode.RAG_QUERY_ERROR, "读取配置失败: " + e.getMessage(), e);
        }
    }

    // ----------------------- 内部工具 -----------------------

    /**
     * 将 knowledgeBaseId 合并进检索过滤条件（不传则原样返回 filter）。
     */
    private Map<String, Object> mergeKbFilter(Map<String, Object> filter, String knowledgeBaseId) {
        if (StringUtils.isBlank(knowledgeBaseId)) {
            return filter;
        }
        Map<String, Object> merged = filter == null ? new HashMap<>() : new HashMap<>(filter);
        merged.put("knowledgeBaseId", knowledgeBaseId);
        return merged;
    }

    private java.io.InputStream toStream(MultipartFile file) {
        try {
            return file.getInputStream();
        } catch (java.io.IOException e) {
            throw new RagException(RagErrorCode.RAG_FILE_EMPTY, "读取上传文件失败", e);
        }
    }

    private SearchResponse toSearchResponse(String query, int topK, List<VectorSearchResult> hits) {
        List<SearchResultItem> items = new ArrayList<>(hits.size());
        for (VectorSearchResult h : hits) {
            items.add(SearchResultItem.builder()
                    .chunkId(h.getChunkId())
                    .documentId(h.getDocumentId())
                    .content(h.getContent())
                    .score(h.getScore())
                    .metadata(h.getMetadata())
                    .build());
        }
        return SearchResponse.builder()
                .query(query)
                .topK(topK)
                .results(items)
                .build();
    }
}
