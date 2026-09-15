package cn.seifly.jharness.plugins.rag.core.api;

import org.pf4j.ExtensionPoint;
import cn.seifly.jharness.plugins.rag.core.model.RagResult;

import java.util.Map;

/**
 * RAG 服务 SPI（对外门面）。
 *
 * <p>当前阶段：{@link #query} 仅执行 Retrieval 并返回相关上下文，
 * 不调用 LLM；答案生成留待未来扩展（见 {@code RagResult.answer}）。
 *
 * <p>继承 PF4J {@link ExtensionPoint}，可注册为 JHarness 插件能力。
 */
public interface RagService extends ExtensionPoint {

    /**
     * RAG 查询：检索相关上下文（Retrieval 与 Generation 解耦）。
     *
     * @param query 用户问题
     * @param topK  返回 Top-K
     */
    RagResult query(String query, int topK);

    /**
     * 带 Metadata 过滤的 RAG 查询。
     */
    RagResult query(String query, int topK, Map<String, Object> filter);
}
