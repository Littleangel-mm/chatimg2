package com.example.chatimg2.exception;

import lombok.Getter;

@Getter
public abstract class AppException extends RuntimeException {

    private final String errorCode;
    private final String userMessage;

    protected AppException(String errorCode, String internalMessage, String userMessage) {
        super(internalMessage);
        this.errorCode = errorCode;
        this.userMessage = userMessage;
    }

    protected AppException(String errorCode, String internalMessage, String userMessage, Throwable cause) {
        super(internalMessage, cause);
        this.errorCode = errorCode;
        this.userMessage = userMessage;
    }
}
