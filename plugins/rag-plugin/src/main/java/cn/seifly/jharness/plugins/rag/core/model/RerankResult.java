package cn.seifly.jharness.plugins.rag.core.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单条重排序结果：指向原候选列表下标，并附带重排模型给出的相关性分数。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RerankResult {

    /** 对应 {@code rerank(query, documents)} 中 documents 的下标 */
    private int index;

    /** 相关性分数（模型输出，越大越相关；不同模型量纲不同，仅用于本次排序） */
    private float score;
}
