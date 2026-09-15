package cn.seifly.jharness.plugin.framework.llm;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Tool call from the LLM.
 *
 * <p>由 models-plugin 迁移至 SDK，作为跨插件共享数据结构。
 */
@Data
@NoArgsConstructor
public class ToolCall {

    private String id;
    private String type;
    private String name;
    private Map<String, Object> arguments;
    private FunctionCall function;

    public ToolCall(String id, String name, Map<String, Object> arguments) {
        this.id = id;
        this.type = "function";
        this.name = name;
        this.arguments = arguments;
    }

    public void setFunction(FunctionCall function) {
        this.function = function;
        if (function != null) {
            this.name = function.getName();
        }
    }

    /**
     * Function call details (OpenAI format)
     */
    @Data
    @NoArgsConstructor
    public static class FunctionCall {
        private String name;
        private String arguments;

        public FunctionCall(String name, String arguments) {
            this.name = name;
            this.arguments = arguments;
        }
    }
}
