package com.example.chatimg2.exception;

import lombok.Getter;

@Getter
public class ApiCallException extends AppException {

    private final Integer httpStatus;
    private final String apiErrorType;
    private final String apiErrorCode;
    private final boolean retryable;

    public ApiCallException(Integer httpStatus, String apiErrorType, String apiErrorCode,
                            boolean retryable, String internalMessage, String userMessage) {
        this(httpStatus, apiErrorType, apiErrorCode, retryable, internalMessage, userMessage, null);
    }

    public ApiCallException(Integer httpStatus, String apiErrorType, String apiErrorCode,
                            boolean retryable, String internalMessage, String userMessage, Throwable cause) {
        super("IMAGE_API_ERROR", internalMessage, userMessage, cause);
        this.httpStatus = httpStatus;
        this.apiErrorType = apiErrorType;
        this.apiErrorCode = apiErrorCode;
        this.retryable = retryable;
    }
}
