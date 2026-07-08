package com.example.chatimg2.service;

import com.example.chatimg2.dto.ChatConversationDto;
import com.example.chatimg2.dto.ChatMessageDto;
import com.example.chatimg2.dto.ChatSyncRequest;
import com.example.chatimg2.dto.ChatSyncResponse;
import com.example.chatimg2.entity.ChatConversation;
import com.example.chatimg2.entity.ChatMessage;
import com.example.chatimg2.entity.UserAccount;
import com.example.chatimg2.repository.ChatConversationRepository;
import com.example.chatimg2.repository.ChatMessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ChatHistoryService {

    private final ChatConversationRepository conversationRepository;
    private final ChatMessageRepository messageRepository;
    private final AuthService authService;

    @Transactional(readOnly = true)
    public ChatSyncResponse load(UserAccount user) {
        List<ChatConversation> conversations = conversationRepository.findTop100ByUserIdOrderByUpdatedAtDesc(user.getId());
        List<Integer> ids = conversations.stream().map(ChatConversation::getId).toList();
        Map<Integer, List<ChatMessage>> messagesByConversation = new HashMap<>();
        if (!ids.isEmpty()) {
            for (ChatMessage message : messageRepository.findByConversationIdInOrderByCreatedAtAsc(ids)) {
                messagesByConversation.computeIfAbsent(message.getConversationId(), ignored -> new ArrayList<>()).add(message);
            }
        }

        ChatSyncResponse response = new ChatSyncResponse();
        response.setConversations(conversations.stream()
                .map(conversation -> toDto(conversation, messagesByConversation.getOrDefault(conversation.getId(), List.of())))
                .toList());
        response.setActiveId(response.getConversations().isEmpty() ? null : response.getConversations().get(0).getClientId());
        return response;
    }

    @Transactional
    public ChatSyncResponse sync(UserAccount user, ChatSyncRequest request) {
        List<ChatConversationDto> incoming = request.getConversations() == null ? List.of() : request.getConversations();
        Set<String> incomingClientIds = new HashSet<>();

        List<ChatConversation> existing = conversationRepository.findTop100ByUserIdOrderByUpdatedAtDesc(user.getId());
        Map<String, ChatConversation> existingByClientId = new HashMap<>();
        for (ChatConversation conversation : existing) {
            if (conversation.getClientId() != null) {
                existingByClientId.put(conversation.getClientId(), conversation);
            }
        }

        for (ChatConversationDto dto : incoming) {
            String clientId = normalizedClientId(dto.getClientId());
            if (clientId == null) {
                continue;
            }
            incomingClientIds.add(clientId);

            ChatConversation conversation = existingByClientId.getOrDefault(clientId, new ChatConversation());
            conversation.setUserId(user.getId());
            conversation.setUserEmail(authService.normalizeEmail(user.getEmail()));
            conversation.setClientId(clientId);
            conversation.setTitle(safeTitle(dto.getTitle()));
            conversation.setConversationType(resolveType(dto.getType()));
            conversation.setCreatedAt(fromMillis(dto.getCreatedAt()));
            conversation.setUpdatedAt(fromMillis(dto.getUpdatedAt()));
            conversation = conversationRepository.save(conversation);

            messageRepository.deleteByConversationId(conversation.getId());
            List<ChatMessage> messages = toMessages(conversation.getId(), dto.getMessages());
            if (!messages.isEmpty()) {
                messageRepository.saveAll(messages);
            }
        }

        for (ChatConversation conversation : existing) {
            String clientId = conversation.getClientId();
            if (clientId != null && !incomingClientIds.contains(clientId)) {
                conversationRepository.delete(conversation);
            }
        }

        ChatSyncResponse response = load(user);
        response.setActiveId(request.getActiveId());
        return response;
    }

    private ChatConversationDto toDto(ChatConversation conversation, List<ChatMessage> messages) {
        ChatConversationDto dto = new ChatConversationDto();
        dto.setClientId(conversation.getClientId());
        dto.setTitle(conversation.getTitle());
        dto.setType(conversation.getConversationType());
        dto.setCreatedAt(toMillis(conversation.getCreatedAt()));
        dto.setUpdatedAt(toMillis(conversation.getUpdatedAt()));
        dto.setMessages(messages.stream()
                .sorted(Comparator.comparing(ChatMessage::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(this::toDto)
                .toList());
        return dto;
    }

    private ChatMessageDto toDto(ChatMessage message) {
        ChatMessageDto dto = new ChatMessageDto();
        dto.setClientId(String.valueOf(message.getId()));
        dto.setRole(message.getRole());
        dto.setContent(message.getContent());
        dto.setSourceImage(message.getImagePath());
        dto.setRecordId(message.getGenerationRecordId());
        dto.setStatus(message.getIntent());
        if ("assistant".equals(message.getRole()) && "failed".equals(message.getIntent())) {
            dto.setErrorMessage(message.getContent());
        }
        dto.setCreatedAt(toMillis(message.getCreatedAt()));
        return dto;
    }

    private List<ChatMessage> toMessages(Integer conversationId, List<ChatMessageDto> dtos) {
        if (dtos == null || dtos.isEmpty()) {
            return List.of();
        }
        List<ChatMessage> messages = new ArrayList<>();
        for (ChatMessageDto dto : dtos) {
            String role = resolveRole(dto.getRole());
            if (role == null) {
                continue;
            }
            ChatMessage message = new ChatMessage();
            message.setConversationId(conversationId);
            message.setRole(role);
            message.setContent(resolveContent(dto, role));
            message.setImagePath(safeImagePath(dto.getSourceImage()));
            message.setGenerationRecordId(dto.getRecordId());
            message.setIntent(dto.getStatus());
            message.setCreatedAt(fromMillis(dto.getCreatedAt()));
            messages.add(message);
        }
        return messages;
    }

    private String resolveContent(ChatMessageDto dto, String role) {
        if ("assistant".equals(role) && "failed".equals(dto.getStatus()) && dto.getErrorMessage() != null) {
            return dto.getErrorMessage();
        }
        return dto.getContent();
    }

    private String normalizedClientId(String clientId) {
        if (clientId == null || clientId.isBlank()) {
            return null;
        }
        return clientId.trim();
    }

    private String safeTitle(String title) {
        String value = title == null || title.isBlank() ? "新对话" : title.trim();
        return value.length() > 255 ? value.substring(0, 255) : value;
    }

    private String resolveType(String type) {
        return "img2img".equals(type) ? "img2img" : "text2img";
    }

    private String resolveRole(String role) {
        if ("user".equals(role) || "assistant".equals(role)) {
            return role;
        }
        return null;
    }

    private String safeImagePath(String imagePath) {
        if (imagePath == null || imagePath.isBlank()) {
            return null;
        }
        if (imagePath.startsWith("data:")) {
            return null;
        }
        return imagePath.length() > 500 ? imagePath.substring(0, 500) : imagePath;
    }

    private LocalDateTime fromMillis(Long millis) {
        if (millis == null || millis <= 0) {
            return LocalDateTime.now();
        }
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault());
    }

    private Long toMillis(LocalDateTime time) {
        if (time == null) {
            return null;
        }
        return time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}
