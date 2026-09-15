package cn.seifly.jharness.plugins.rag.core.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 检索结果条目。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchResultItem {
    private String chunkId;
    private String documentId;
    private String content;
    private float score;
    private Map<String, Object> metadata;
}
