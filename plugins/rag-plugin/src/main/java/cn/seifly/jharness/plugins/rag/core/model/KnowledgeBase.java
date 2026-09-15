package cn.seifly.jharness.plugins.rag.core.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 知识库（多知识库隔离单元）。
 *
 * <p>元信息持久化于 Redis（Hash {@code rag:kb:{id}} + 索引 Set {@code rag:kbs}），
 * 仅承载配置与展示信息；具体文档 / 向量按 {@code knowledgeBaseId} 归属到各知识库。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeBase {

    /** 知识库 ID（唯一，作为隔离主键） */
    private String knowledgeBaseId;

    /** 展示名称 */
    private String name;

    /** 描述 */
    private String description;

    /** 租户 ID */
    private String tenantId;

    /** 创建时间（ms） */
    private long createdAt;

    /** 更新时间（ms） */
    private long updatedAt;

    /** 文档数（运行时统计，非持久化字段） */
    private long docCount;
}
