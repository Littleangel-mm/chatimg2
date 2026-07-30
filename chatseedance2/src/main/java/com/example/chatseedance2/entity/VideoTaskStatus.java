package com.example.chatseedance2.entity;

public enum VideoTaskStatus {
    SUBMITTING,
    PENDING,
    PROCESSING,
    SUBMISSION_UNKNOWN,
    POLLING_DELAYED,
    COMPLETED,
    FAILED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED;
    }

    public boolean isRecoverable() {
        return !isTerminal();
    }
}
