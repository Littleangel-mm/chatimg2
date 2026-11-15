package com.example.chatseedance2.exception;

import org.springframework.http.HttpStatus;

public class BusinessException extends AppException {

    public BusinessException(String errorCode, String message) {
        super(HttpStatus.BAD_REQUEST, errorCode, message, message);
    }

    public BusinessException(String errorCode, String message, Throwable cause) {
        super(HttpStatus.BAD_REQUEST, errorCode, message, message, cause);
    }
}
