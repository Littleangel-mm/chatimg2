package com.example.chatimg2.exception;

public class ImageDownloadException extends AppException {

    public ImageDownloadException(String internalMessage, Throwable cause) {
        super("IMAGE_DOWNLOAD_ERROR", internalMessage, "图片保存失败，请稍后重试", cause);
    }

    public ImageDownloadException(String internalMessage) {
        super("IMAGE_DOWNLOAD_ERROR", internalMessage, "图片保存失败，请稍后重试");
    }
}
