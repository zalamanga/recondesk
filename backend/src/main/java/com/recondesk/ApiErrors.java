package com.recondesk;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingRequestValueException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;

@RestControllerAdvice
class ApiErrors {
    @ExceptionHandler(ApiException.class)
    ResponseEntity<?> application(ApiException error) { return response(error.status, error.code, error.getMessage()); }
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<?> upload(MaxUploadSizeExceededException error) { return response(413, "UPLOAD_LIMIT", "Each source must be at most 1 MiB."); }
    @ExceptionHandler({HttpMessageNotReadableException.class, MissingRequestValueException.class, MethodArgumentTypeMismatchException.class,
            HttpMediaTypeNotSupportedException.class, MultipartException.class})
    ResponseEntity<?> validation(Exception error) { return response(400, "VALIDATION", "The request is incomplete or has an invalid format."); }
    @ExceptionHandler({NoResourceFoundException.class, HttpRequestMethodNotSupportedException.class})
    ResponseEntity<?> missing(Exception error) { return response(404, "NOT_FOUND", "Route not found."); }
    @ExceptionHandler(Exception.class)
    ResponseEntity<?> failure(Exception error) {
        org.slf4j.LoggerFactory.getLogger(ApiErrors.class).error("Request failed: {}", error.getClass().getSimpleName());
        return response(500, "INTERNAL_ERROR", "The request could not be completed. Retry or check server availability.");
    }
    private ResponseEntity<Map<String, String>> response(int status, String code, String message) { return ResponseEntity.status(status).body(Map.of("code", code, "message", message)); }
}
