package cn.seifly.jharness.plugin.framework;

import lombok.Getter;

/**
 * 插件框架基础异常类。
 *
 * <p>所有插件 / SDK 内部异常建议继承此类，便于统一错误码处理。
 * 继承自 {@link RuntimeException}，无需在方法签名中声明 throws。
 */
@Getter
public class BaseException extends RuntimeException {

    private final String errorCode;

    public BaseException(String message) {
        super(message);
        this.errorCode = "UNKNOWN";
    }

    public BaseException(String message, Throwable cause) {
        super(message, cause);
        this.errorCode = "UNKNOWN";
    }

    public BaseException(Throwable cause) {
        super(cause);
        this.errorCode = "UNKNOWN";
    }

    public BaseException(String message, String errorCode) {
        super(message);
        this.errorCode = errorCode;
    }

    public BaseException(String message, Throwable cause, String errorCode) {
        super(message, cause);
        this.errorCode = errorCode;
    }
}
