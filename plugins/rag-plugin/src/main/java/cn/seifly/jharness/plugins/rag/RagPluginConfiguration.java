package cn.seifly.jharness.plugins.rag;

import cn.seifly.jharness.plugins.rag.controller.KnowledgeBaseController;
import cn.seifly.jharness.plugins.rag.core.api.*;
import cn.seifly.jharness.plugins.rag.chunk.RecursiveTextChunker;
import cn.seifly.jharness.plugins.rag.controller.RagController;
import cn.seifly.jharness.plugins.rag.embedding.Qwen3EmbeddingService;
import cn.seifly.jharness.plugins.rag.core.exception.RagErrorCode;
import cn.seifly.jharness.plugins.rag.core.exception.RagException;
import cn.seifly.jharness.plugins.rag.parser.CompositeDocumentParser;
import cn.seifly.jharness.plugins.rag.parser.DocxDocumentParser;
import cn.seifly.jharness.plugins.rag.parser.MarkdownDocumentParser;
import cn.seifly.jharness.plugins.rag.parser.PdfDocumentParser;
import cn.seifly.jharness.plugins.rag.parser.TxtDocumentParser;
import cn.seifly.jharness.plugins.rag.rerank.OllamaRerankerService;
import cn.seifly.jharness.plugins.rag.retrieval.DefaultRetrievalService;
import cn.seifly.jharness.plugins.rag.service.DocumentServiceImpl;
import cn.seifly.jharness.plugins.rag.service.KnowledgeBaseServiceImpl;
import cn.seifly.jharness.plugins.rag.service.RagServiceImpl;
import cn.seifly.jharness.plugins.rag.vectorstore.InMemoryVectorStore;
import cn.seifly.jharness.plugins.rag.vectorstore.RedisVectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * RAG 插件内部 Spring 配置（运行在插件私有子应用上下文中）。
 *
 * <p>职责：按 {@code rag.vector-store} / {@code embedding.provider} 配置<b>选择具体实现</b>，
 * 完成全部 Bean 装配。业务 Bean 只依赖接口，具体实现在此集中决定——
 * 切换向量库 / Embedding 模型时，仅需改配置，业务代码零修改。
 */
@Configuration
public class RagPluginConfiguration {

    private static final Logger log = LoggerFactory.getLogger(RagPluginConfiguration.class);


    // ===================== Embedding =====================

    @Bean
    public EmbeddingService embeddingService(Environment env) {

        String provider = env.getProperty("embedding.provider", "qwen3");
        // qwen3 / openai 均走 OpenAI 兼容 /v1/embeddings HTTP 接口
        if ("qwen3".equalsIgnoreCase(provider) || "openai".equalsIgnoreCase(provider)) {
            String baseUrl = env.getProperty("embedding.api.base-url", "http://127.0.0.1:9000/v1");
            String model = env.getProperty("embedding.api.model", "Qwen3-Embedding-0.6B");
            int dimension = Integer.parseInt(env.getProperty("embedding.api.dimensions", "1024"));
            String apiKey = env.getProperty("embedding.api.key", "");
            return new Qwen3EmbeddingService(baseUrl, model, dimension, apiKey);
        }
        throw new RagException(RagErrorCode.RAG_EMBEDDING_FAILED,
                "不支持的 embedding.provider: " + provider + "（当前支持 qwen3 / openai）");
    }

    // ===================== 解析器 =====================

    @Bean
    public TxtDocumentParser txtDocumentParser() {
        return new TxtDocumentParser();
    }

    @Bean
    public MarkdownDocumentParser markdownDocumentParser() {
        return new MarkdownDocumentParser();
    }

    @Bean
    public PdfDocumentParser pdfDocumentParser() {
        return new PdfDocumentParser();
    }

    @Bean
    public DocxDocumentParser docxDocumentParser() {
        return new DocxDocumentParser();
    }

    @Bean
    public CompositeDocumentParser documentParser(List<DocumentParser> parsers) {
        return new CompositeDocumentParser(parsers);
    }

    // ===================== 切分器 =====================

    @Bean
    public TextChunker textChunker(Environment env) {
        int size = Integer.parseInt(env.getProperty("rag.chunk.size", "800"));
        int overlap = Integer.parseInt(env.getProperty("rag.chunk.overlap", "100"));
        return new RecursiveTextChunker(size, overlap);
    }

    // ===================== 向量存储 =====================

    @Bean
    public VectorStore vectorStore(Environment env, EmbeddingService embeddingService) {
        String store = env.getProperty("rag.vector-store", "redis");
        if ("redis".equalsIgnoreCase(store)) {
            String host = env.getProperty("redis.host", "localhost");
            int port = Integer.parseInt(env.getProperty("redis.port", "6379"));
            return new RedisVectorStore(host, port, embeddingService);
        }
        if ("memory".equalsIgnoreCase(store)) {
            return new InMemoryVectorStore();
        }
        throw new RagException(RagErrorCode.RAG_INVALID_PARAM,
                "不支持的 rag.vector-store: " + store + "（当前支持 redis / memory）");
    }

    @Bean
    public DocumentRepository documentRepository(VectorStore vectorStore) {
        // 当前 Redis / Memory 实现均同时实现 VectorStore 与 DocumentRepository
        return (DocumentRepository) vectorStore;
    }

    // ===================== 重排序（Rerank） =====================

    /**
     * 重排器 Bean：仅当 {@code rag.retrieval.rerank.enabled=true} 时创建。
     * 返回 null（未启用）时，{@link #retrievalService} 通过 {@link ObjectProvider} 拿到 null，
     * 检索流程自动退化为纯向量召回，行为等价于旧版。
     */
    @Bean
    public RerankerService rerankerService(Environment env) {
        boolean enabled = Boolean.parseBoolean(env.getProperty("rag.retrieval.rerank.enabled", "false"));
        if (!enabled) {
            log.info("[Rerank] 重排序未启用（rag.retrieval.rerank.enabled=false），检索走纯向量召回");
            return null;
        }
        String provider = env.getProperty("rerank.provider", "ollama");
        // ollama / openai / vllm 均走 OpenAI 兼容 /v1/rerank 客户端（按 base-url 区分具体端点）
        if ("ollama".equalsIgnoreCase(provider) || "openai".equalsIgnoreCase(provider)
                || "vllm".equalsIgnoreCase(provider)) {
            String baseUrl = env.getProperty("rerank.api.base-url", "http://localhost:11434/api");
            String model = env.getProperty("rerank.api.model", "qwen3-reranker:0.6b");
            String apiKey = env.getProperty("rerank.api.key", "");
            return new OllamaRerankerService(baseUrl, model, apiKey);
        }
        throw new RagException(RagErrorCode.RAG_RERANK_FAILED,
                "不支持的 rerank.provider: " + provider + "（当前支持 ollama）");
    }

    // ===================== 服务 =====================

    @Bean
    public RetrievalService retrievalService(EmbeddingService embeddingService, VectorStore vectorStore,
                                             ObjectProvider<RerankerService> rerankerProvider, Environment env) {
        RerankerService reranker = rerankerProvider.getIfAvailable();
        int candidateSize = Integer.parseInt(env.getProperty("rag.retrieval.rerank.candidate-size", "20"));
        return new DefaultRetrievalService(embeddingService, vectorStore, reranker, candidateSize);
    }

    @Bean
    public DocumentService documentService(DocumentParser documentParser, TextChunker textChunker,
                                            EmbeddingService embeddingService, VectorStore vectorStore,
                                            DocumentRepository documentRepository) {
        return new DocumentServiceImpl(documentParser, textChunker, embeddingService, vectorStore, documentRepository);
    }

    @Bean
    public RagService ragService(RetrievalService retrievalService) {
        return new RagServiceImpl(retrievalService);
    }

    // ===================== 知识库管理 =====================

    @Bean
    public KnowledgeBaseService knowledgeBaseService(Environment env, DocumentService documentService) {
        String host = env.getProperty("redis.host", "localhost");
        int port = Integer.parseInt(env.getProperty("redis.port", "6379"));
        return new KnowledgeBaseServiceImpl(documentService, host, port);
    }

    // ===================== 控制器 =====================

    @Bean
    public RagController ragController(DocumentService documentService, RagService ragService,
                                       RetrievalService retrievalService) {
        return new RagController(documentService, ragService, retrievalService);
    }

    @Bean
    public KnowledgeBaseController knowledgeBaseController(KnowledgeBaseService kbService) {
        return new KnowledgeBaseController(kbService);
    }

    /**
     * 知识库管理 Web UI 控制器（{@code GET /rag/} → 单页应用）。
     */
    @Bean
    public cn.seifly.jharness.plugins.rag.controller.RagWebController ragWebController() {
        return new cn.seifly.jharness.plugins.rag.controller.RagWebController();
    }
}
