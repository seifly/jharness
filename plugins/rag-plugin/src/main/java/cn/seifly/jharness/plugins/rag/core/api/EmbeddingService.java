package cn.seifly.jharness.plugins.rag.core.api;

import org.pf4j.ExtensionPoint;

import java.util.List;

/**
 * Embedding 服务 SPI。
 *
 * <p>与具体模型解耦：业务层只依赖本接口。
 * 当前实现 {@code Qwen3EmbeddingService}（Qwen3-Embedding-0.6B，本地 ONNX）；
 * 未来可替换为 BGE / OpenAI / Ollama 等，业务代码零修改。
 *
 * <p>继承 PF4J {@link ExtensionPoint}，可融入 JHarness 插件扩展体系。
 */
public interface EmbeddingService extends ExtensionPoint {

    /**
     * 对单段文本生成 Embedding。
     */
    float[] embed(String text);

    /**
     * 批量生成（实现应内部批处理以提升吞吐）。
     */
    List<float[]> embedBatch(List<String> texts);

    /**
     * 向量维度（<b>禁止硬编码</b>，向量库的索引维度必须取自此值）。
     */
    int dimension();
}
