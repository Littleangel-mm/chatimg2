package com.example.chatseedance2.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class ProviderApiException extends AppException {

    private final int providerHttpStatus;
    private final String providerErrorCode;
    private final boolean retryable;
    private final boolean ambiguousSubmission;

    public ProviderApiException(String message, int providerHttpStatus, String providerErrorCode,
                                boolean retryable, boolean ambiguousSubmission) {
        super(HttpStatus.BAD_GATEWAY, "PROVIDER_API_ERROR", message, "视频服务暂时不可用，请稍后重试");
        this.providerHttpStatus = providerHttpStatus;
        this.providerErrorCode = providerErrorCode;
        this.retryable = retryable;
        this.ambiguousSubmission = ambiguousSubmission;
    }

    public ProviderApiException(String message, boolean retryable, boolean ambiguousSubmission, Throwable cause) {
        super(HttpStatus.BAD_GATEWAY, "PROVIDER_NETWORK_ERROR", message,
                "视频服务连接异常，请稍后重试", cause);
        this.providerHttpStatus = 0;
        this.providerErrorCode = null;
        this.retryable = retryable;
        this.ambiguousSubmission = ambiguousSubmission;
    }
}
