package cn.seifly.jharness.plugins.rag.core.api;

import cn.seifly.jharness.plugins.rag.core.model.KnowledgeBase;

import java.util.List;

/**
 * 知识库管理 SPI：多知识库（tenant / knowledgeBase 维度）的创建、查询、隔离与管理。
 */
public interface KnowledgeBaseService {

    /**
     * 创建请求。
     *
     * @param knowledgeBaseId 可选；留空自动生成 UUID。仅允许字母、数字、下划线、横线
     * @param name            展示名称（必填）
     * @param description     描述（可选）
     * @param tenantId        租户 ID（可选，默认 default）
     */
    record CreateRequest(String knowledgeBaseId, String name, String description, String tenantId) {
    }

    /**
     * 列出全部知识库（附带各库文档数）。
     */
    List<KnowledgeBase> list();

    /**
     * 获取单个知识库。
     */
    KnowledgeBase get(String id);

    /**
     * 创建知识库。
     */
    KnowledgeBase create(CreateRequest req);

    /**
     * 删除知识库，并联动清理其下全部文档。
     */
    void delete(String id);
}
