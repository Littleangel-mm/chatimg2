package com.example.chatimg2.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AssistantChatResponse {
    private String conversationId;
    private String clientMessageId;
    private String assistantMessageId;
    private String reply;
    private String prompt;
    private String negativePrompt;
    private List<String> questions = new ArrayList<>();
    private List<String> suggestions = new ArrayList<>();
    private Long createdAt;
}
