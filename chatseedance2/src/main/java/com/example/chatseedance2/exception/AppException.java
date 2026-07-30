package com.example.chatseedance2.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class AppException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;
    private final String userMessage;

    public AppException(HttpStatus status, String errorCode, String internalMessage, String userMessage) {
        super(internalMessage);
        this.status = status;
        this.errorCode = errorCode;
        this.userMessage = userMessage;
    }

    public AppException(HttpStatus status, String errorCode, String internalMessage,
                        String userMessage, Throwable cause) {
        super(internalMessage, cause);
        this.status = status;
        this.errorCode = errorCode;
        this.userMessage = userMessage;
    }
}
