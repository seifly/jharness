package cn.seifly.jharness.plugins.rag.core.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * RAG 查询结果。
 *
 * <p>当前阶段：contexts 仅包含检索到的相关 Chunk（检索与生成解耦）。
 * 未来接入 LLM 后，可在此扩展 answer 字段，而不破坏现有消费方。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagResult {

    /** 原始问题 */
    private String query;

    /** 命中的相关上下文 Chunk（content + score + metadata） */
    private List<ContextItem> contexts;

    /**
     * 未来 LLM 生成的答案（当前未填充）。
     * 预留字段，避免 Retrieval 与 Generation 写死在一起。
     */
    private String answer;

    /** 预留：Prompt 等扩展信息 */
    private Map<String, Object> extension;
}
