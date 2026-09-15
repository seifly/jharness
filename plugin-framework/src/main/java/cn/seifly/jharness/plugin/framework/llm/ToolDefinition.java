package cn.seifly.jharness.plugin.framework.llm;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Tool definition for LLM function calling.
 *
 * <p>由 models-plugin 迁移至 SDK，作为跨插件共享数据结构。
 */
@Data
public class ToolDefinition {

    private String type;
    private ToolFunctionDefinition function;

    public ToolDefinition() {
        this.type = "function";
    }

    public ToolDefinition(String name, String description, Map<String, Object> parameters) {
        this.type = "function";
        this.function = new ToolFunctionDefinition(name, description, parameters);
    }

    /**
     * Tool function definition
     */
    @Data
    @NoArgsConstructor
    public static class ToolFunctionDefinition {
        private String name;
        private String description;
        private Map<String, Object> parameters;

        public ToolFunctionDefinition(String name, String description, Map<String, Object> parameters) {
            this.name = name;
            this.description = description;
            this.parameters = parameters;
        }
    }
}
