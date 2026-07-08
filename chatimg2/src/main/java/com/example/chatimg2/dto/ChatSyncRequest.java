package com.example.chatimg2.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class ChatSyncRequest {
    private String activeId;
    private List<ChatConversationDto> conversations = new ArrayList<>();
}
