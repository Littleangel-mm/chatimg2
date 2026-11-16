package com.example.chatimg2.dto;

import lombok.Data;

@Data
public class AssistantChatRequest {
    private String conversationId;
    private String clientMessageId;
    private String message;
}
