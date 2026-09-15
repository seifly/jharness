package cn.seifly.jharness.plugin.framework.tools;

import cn.seifly.jharness.plugin.framework.BaseException;

/**
 * 工具执行相关异常。
 */
public class ToolException extends BaseException {

    public ToolException(String message) {
        super(message, "TOOL_ERROR");
    }

    public ToolException(String message, Throwable cause) {
        super(message, cause, "TOOL_ERROR");
    }

    public ToolException(Throwable cause) {
        super(cause.getMessage(), cause, "TOOL_ERROR");
    }

    public ToolException(String message, String errorCode) {
        super(message, errorCode);
    }

    public ToolException(String message, Throwable cause, String errorCode) {
        super(message, cause, errorCode);
    }
}
