package cn.seifly.jharness.plugins.rag.core.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * RAG 查询响应体（当前返回检索上下文，answer 预留）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueryResponse {
    private String query;
    private int topK;
    private List<ContextItem> contexts;
}
