package com.example.chatimg2.service;

public final class GenerationState {

    public static final String PROCESSING = "processing";
    public static final String COMPLETED = "completed";
    public static final String FAILED = "failed";
    public static final String INTERRUPTED_MESSAGE =
            "\u751f\u6210\u4efb\u52a1\u4e2d\u65ad\uff0c\u5df2\u9000\u56de\u79ef\u5206\uff0c\u8bf7\u91cd\u65b0\u751f\u6210";

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

    public static boolean isInterruptedFailure(String errorMessage) {
        return errorMessage != null
                && errorMessage.contains("\u751f\u6210\u4efb\u52a1\u4e2d\u65ad")
                && errorMessage.contains("\u5df2\u9000\u56de\u79ef\u5206");
    }
}
