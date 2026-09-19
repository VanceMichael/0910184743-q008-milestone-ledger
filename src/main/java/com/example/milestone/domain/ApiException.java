package com.example.milestone.domain;

/** API 错误：HTTP 状态码 + 机器可读错误码。 */
public final class ApiException extends RuntimeException {

    private final int status;
    private final String code;

    public ApiException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() { return status; }
    public String code() { return code; }

    public static ApiException badRequest(String message) {
        return new ApiException(400, "VALIDATION", message);
    }

    public static ApiException unauthorized(String code, String message) {
        return new ApiException(401, code, message);
    }

    public static ApiException forbidden(String message) {
        return new ApiException(403, "FORBIDDEN", message);
    }

    public static ApiException notFound(String what) {
        return new ApiException(404, "NOT_FOUND", what + " 不存在");
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(409, code, message);
    }
}
