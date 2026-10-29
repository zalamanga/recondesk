package com.recondesk;

public class ApiException extends RuntimeException {
    final int status;
    final String code;
    public ApiException(int status, String code, String message) { super(message); this.status = status; this.code = code; }
    static ApiException invalid(String message) { return new ApiException(400, "VALIDATION", message); }
    static ApiException conflict(String message) { return new ApiException(409, "CONFLICT", message); }
    static ApiException missing() { return new ApiException(404, "NOT_FOUND", "Record not found."); }
    static ApiException forbidden(String message) { return new ApiException(403, "FORBIDDEN", message); }
}
