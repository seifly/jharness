package cn.seifly.jharness.plugins.rag.core.exception;

import cn.seifly.jharness.plugin.framework.BaseException;
import lombok.AccessLevel;
import lombok.Getter;

/**
 * RAG 统一业务异常。
 *
 * <p>继承 JHarness SDK 的 {@link BaseException}（携带 errorCode），
 * 所有 RAG 内部错误统一抛出本异常，由 Controller 转换为结构化响应。
 */
@Getter
public class RagException extends BaseException {

    @Getter(AccessLevel.NONE)
    private final RagErrorCode errorCode;

    public RagException(RagErrorCode errorCode) {
        super(errorCode.getDefaultMessage(), errorCode.name());
        this.errorCode = errorCode;
    }

    public RagException(RagErrorCode errorCode, String message) {
        super(message, errorCode.name());
        this.errorCode = errorCode;
    }

    public RagException(RagErrorCode errorCode, String message, Throwable cause) {
        super(message, cause, errorCode.name());
        this.errorCode = errorCode;
    }

    public RagException(RagErrorCode errorCode, Throwable cause) {
        super(errorCode.getDefaultMessage(), cause, errorCode.name());
        this.errorCode = errorCode;
    }

    /**
     * 获取原始枚举错误码；父类 {@code getErrorCode()} 返回其 name 字符串，供统一响应使用。
     */
    public RagErrorCode getRagErrorCode() {
        return errorCode;
    }
}
