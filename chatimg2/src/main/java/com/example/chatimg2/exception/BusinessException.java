package com.example.chatimg2.exception;

public class BusinessException extends AppException {

    public BusinessException(String errorCode, String message) {
        super(errorCode, message, message);
    }

    public BusinessException(String errorCode, String message, Throwable cause) {
        super(errorCode, message, message, cause);
    }
}
