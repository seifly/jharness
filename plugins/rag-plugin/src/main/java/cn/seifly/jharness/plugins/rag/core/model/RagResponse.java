package cn.seifly.jharness.plugins.rag.core.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * RAG 统一 REST 响应（code 为字符串错误码，区别于宿主 int 型 ApiResult）。
 *
 * <pre>{ "code": "RAG_xxx" | "OK", "message": "...", "data": ... }</pre>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RagResponse<T> {

    /** 错误码字符串；成功为 "OK" */
    private String code;

    /** 提示信息 */
    private String message;

    /** 业务数据 */
    private T data;

    public static <T> RagResponse<T> ok(T data) {
        return new RagResponse<>("OK", "success", data);
    }

    public static <T> RagResponse<T> error(String code, String message) {
        return new RagResponse<>(code, message, null);
    }
}
