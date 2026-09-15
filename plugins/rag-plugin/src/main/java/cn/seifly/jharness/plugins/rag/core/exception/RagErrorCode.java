package cn.seifly.jharness.plugins.rag.core.exception;

import lombok.Getter;

/**
 * RAG 业务错误码。
 *
 * <p>统一返回结构：{ "code": "RAG_xxx", "message": "...", "data": null }，
 * 不直接暴露堆栈。
 */
@Getter
public enum RagErrorCode {

    RAG_FILE_EMPTY("文件为空，请上传有效内容"),
    RAG_FILE_UNSUPPORTED("不支持的文件类型，仅支持 .txt .md .pdf .docx"),
    RAG_PARSE_FAILED("文档解析失败"),
    RAG_CHUNK_EMPTY("文本切分后为空，无法生成 Chunk"),
    RAG_EMBEDDING_FAILED("Embedding 生成失败"),
    RAG_EMBEDDING_MODEL_NOT_FOUND("Embedding 模型未找到，请检查 embedding.model-path 配置"),
    RAG_RERANK_FAILED("Rerank 重排序失败"),
    RAG_VECTOR_DIMENSION("向量维度不一致（与向量库索引维度不匹配）"),
    RAG_REDIS_CONNECTION("Redis 连接失败，请确认 Redis 已启动且地址正确"),
    RAG_REDIS_INDEX_MISSING("Redis 向量索引不存在，请先初始化索引"),
    RAG_QUERY_EMPTY("查询内容为空"),
    RAG_DOC_NOT_FOUND("文档不存在"),
    RAG_QUERY_ERROR("Reg查询失败"),
    RAG_INVALID_PARAM("参数非法");

    private final String defaultMessage;

    RagErrorCode(String defaultMessage) {
        this.defaultMessage = defaultMessage;
    }
}
