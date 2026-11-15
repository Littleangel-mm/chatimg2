package com.example.chatimg2.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class ChatConversationDto {
    private String clientId;
    private String title;
    private String type;
    private Long createdAt;
    private Long updatedAt;
    private List<ChatMessageDto> messages = new ArrayList<>();
}
