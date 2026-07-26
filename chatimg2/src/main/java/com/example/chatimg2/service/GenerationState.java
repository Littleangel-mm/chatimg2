package com.example.chatimg2.service;

public final class GenerationState {

    public static final String PROCESSING = "processing";
    public static final String COMPLETED = "completed";
    public static final String FAILED = "failed";

    private GenerationState() {
    }

    public static boolean isProcessing(String status) {
        return PROCESSING.equals(status);
    }

    public static boolean isCompleted(String status) {
        return COMPLETED.equals(status);
    }

    public static boolean isFailed(String status) {
        return FAILED.equals(status);
    }
}
