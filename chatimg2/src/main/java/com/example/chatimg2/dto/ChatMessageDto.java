package com.example.chatimg2.dto;

import lombok.Data;

@Data
public class ChatMessageDto {
    private String clientId;
    private String role;
    private String content;
    private String sourceImage;
    private Integer recordId;
    private String recordImagePath;
    private String recordSourceImagePath;
    private Boolean recordRemoteImageAvailable;
    private String recordTaskCode;
    private String recordGenerationType;
    private Boolean recordRecoverable;
    private String status;
    private String errorMessage;
    private Long createdAt;
}
