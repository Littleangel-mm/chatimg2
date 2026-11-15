package com.example.chatseedance2.controller;

import com.example.chatseedance2.dto.ApiResponse;
import com.example.chatseedance2.exception.AppException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(AppException.class)
    public ResponseEntity<ApiResponse<Void>> handleAppException(AppException error) {
        if (error.getStatus().is5xxServerError()) {
            log.error("Application error: code={}", error.getErrorCode(), error);
        }
        return ResponseEntity.status(error.getStatus())
                .body(ApiResponse.error(error.getStatus().value(), error.getUserMessage()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public ResponseEntity<ApiResponse<Void>> handleValidation(Exception error) {
        String message = error instanceof MethodArgumentNotValidException validation
                ? validation.getBindingResult().getAllErrors().stream()
                        .findFirst().map(item -> item.getDefaultMessage()).orElse("请求参数无效")
                : "请求参数无效";
        return ResponseEntity.badRequest().body(ApiResponse.error(400, message));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadable(HttpMessageNotReadableException error) {
        return ResponseEntity.badRequest().body(ApiResponse.error(400, "请求内容格式无效"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception error) {
        log.error("Unexpected request failure", error);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(500, "请求处理失败，请稍后重试"));
    }
}
