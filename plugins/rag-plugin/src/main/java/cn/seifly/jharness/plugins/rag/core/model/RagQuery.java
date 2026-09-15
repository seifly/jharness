package cn.seifly.jharness.plugins.rag.core.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * RAG 检索 / 生成请求。
 *
 * <p>Retrieval（检索 Top-K 上下文）与 Generation（LLM 生成答案）解耦：
 * 当前阶段仅执行 Retrieval，Generation 由未来接入的 LLM 完成。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagQuery {

    /** 用户问题 */
    private String query;

    /** 返回 Top-K 个相关 Chunk */
    private int topK;

    /** 可选 Metadata 过滤条件（tenantId / knowledgeBaseId ...） */
    private Map<String, Object> filter;
}
