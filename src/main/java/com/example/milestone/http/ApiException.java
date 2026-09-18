package com.example.milestone.http;

/** 业务/协议错误，映射为 HTTP 状态码与机器可读错误码。 */
public class ApiException extends RuntimeException {
    public final int status;
    public final String code;

    public ApiException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static ApiException notFound(String code, String message) {
        return new ApiException(404, code, message);
    }

    public static ApiException forbidden(String message) {
        return new ApiException(403, "FORBIDDEN", message);
    }

    public static ApiException unprocessable(String code, String message) {
        return new ApiException(422, code, message);
    }
}
