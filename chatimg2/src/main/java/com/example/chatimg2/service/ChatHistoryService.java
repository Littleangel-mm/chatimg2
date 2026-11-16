package com.example.chatimg2.service;

import com.example.chatimg2.dto.ChatConversationDto;
import com.example.chatimg2.dto.ChatMessageDto;
import com.example.chatimg2.dto.ChatSyncRequest;
import com.example.chatimg2.dto.ChatSyncResponse;
import com.example.chatimg2.entity.ChatConversation;
import com.example.chatimg2.entity.ChatMessage;
import com.example.chatimg2.entity.GenerationRecord;
import com.example.chatimg2.entity.UserAccount;
import com.example.chatimg2.repository.ChatConversationRepository;
import com.example.chatimg2.repository.ChatMessageRepository;
import com.example.chatimg2.repository.GenerationRecordRepository;
import com.example.chatimg2.repository.UserAccountRepository;
import com.example.chatseedance2.dto.VideoGenerationResponse;
import com.example.chatseedance2.entity.VideoGenerationTask;
import com.example.chatseedance2.repository.VideoGenerationTaskRepository;
import com.example.chatseedance2.service.VideoTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChatHistoryService {

    private static final int MAX_MESSAGE_CHARS = 20_000;
    private static final long MAX_CLOCK_SKEW_MILLIS = 5 * 60 * 1000L;

    private final ChatConversationRepository conversationRepository;
    private final ChatMessageRepository messageRepository;
    private final GenerationRecordRepository generationRecordRepository;
    private final UserAccountRepository userRepository;
    private final AuthService authService;
    private final GenerationRecordViewService recordViewService;
    private final VideoGenerationTaskRepository videoTaskRepository;
    private final VideoTaskMapper videoTaskMapper;

    @Transactional(readOnly = true)
    public ChatSyncResponse load(UserAccount user) {
        List<ChatConversation> conversations = conversationRepository
                .findByUserIdAndDeletedAtIsNullOrderByUpdatedAtDesc(user.getId());
        List<Integer> ids = conversations.stream().map(ChatConversation::getId).toList();
        Map<Integer, List<ChatMessage>> messagesByConversation = new HashMap<>();
        Map<Integer, GenerationRecord> generationRecords = new HashMap<>();
        Map<String, GenerationRecord> generationRecordsByClientMessage = new HashMap<>();
        Map<String, VideoGenerationTask> videoTasksByCode = new HashMap<>();
        Map<String, VideoGenerationTask> videoTasksByClientMessage = new HashMap<>();
        if (!ids.isEmpty()) {
            List<ChatMessage> messages = messageRepository.findByConversationIdInOrderByCreatedAtAscIdAsc(ids);
            for (ChatMessage message : messages) {
                messagesByConversation.computeIfAbsent(message.getConversationId(), ignored -> new ArrayList<>()).add(message);
            }
            List<Integer> recordIds = messages.stream()
                    .map(ChatMessage::getGenerationRecordId)
                    .filter(java.util.Objects::nonNull)
                    .distinct()
                    .toList();
            if (!recordIds.isEmpty()) {
                generationRecordRepository.findAllById(recordIds)
                        .forEach(record -> generationRecords.put(record.getId(), record));
            }
            List<String> assistantClientIds = messages.stream()
                    .filter(message -> "assistant".equals(message.getRole()))
                    .filter(message -> message.getGenerationRecordId() == null)
                    .map(ChatMessage::getClientId)
                    .filter(java.util.Objects::nonNull)
                    .filter(clientId -> !clientId.isBlank())
                    .distinct()
                    .toList();
            if (!assistantClientIds.isEmpty()) {
                generationRecordRepository
                        .findByUserIdAndClientMessageIdInOrderByCreatedAtDesc(user.getId(), assistantClientIds)
                        .forEach(record -> generationRecordsByClientMessage
                                .putIfAbsent(record.getClientMessageId(), record));
                videoTaskRepository
                        .findByUserIdAndClientMessageIdInOrderByCreatedAtDesc(user.getId(), assistantClientIds)
                        .forEach(task -> videoTasksByClientMessage
                                .putIfAbsent(task.getClientMessageId(), task));
            }
            List<String> videoTaskCodes = messages.stream()
                    .map(ChatMessage::getVideoTaskCode)
                    .filter(java.util.Objects::nonNull)
                    .filter(code -> !code.isBlank())
                    .distinct()
                    .toList();
            if (!videoTaskCodes.isEmpty()) {
                videoTaskRepository.findByTaskCodeIn(videoTaskCodes).stream()
                        .filter(task -> user.getId().equals(task.getUserId()))
                        .forEach(task -> videoTasksByCode.put(task.getTaskCode(), task));
            }
        }

        ChatSyncResponse response = new ChatSyncResponse();
        response.setDeletedClientIds(conversationRepository.findDeletedClientIdsByUserId(user.getId()).stream()
                .filter(clientId -> !clientId.isBlank())
                .distinct()
                .toList());
        response.setConversations(conversations.stream()
                .map(conversation -> toDto(
                        conversation,
                        messagesByConversation.getOrDefault(conversation.getId(), List.of()),
                        generationRecords,
                        generationRecordsByClientMessage,
                        videoTasksByCode,
                        videoTasksByClientMessage))
                .toList());
        response.setActiveId(response.getConversations().isEmpty() ? null : response.getConversations().get(0).getClientId());
        return response;
    }

    @Transactional
    public ChatSyncResponse sync(UserAccount user, ChatSyncRequest request) {
        lockUserForWrite(user);
        List<ChatConversationDto> incoming = request.getConversations() == null ? List.of() : request.getConversations();
        int messageCount = incoming.stream()
                .mapToInt(conversation -> conversation.getMessages() == null ? 0 : conversation.getMessages().size())
                .sum();
        log.info("Chat sync start: user={}, conversations={}, messages={}",
                user.getEmail(), incoming.size(), messageCount);
        Set<String> deletedClientIds = normalizedClientIds(request.getDeletedClientIds());
        Set<String> processedConversationIds = new HashSet<>();

        List<ChatConversation> existing = conversationRepository.findByUserIdOrderByUpdatedAtDesc(user.getId());
        Map<String, ChatConversation> existingByClientId = new HashMap<>();
        Set<String> deletedExistingClientIds = new HashSet<>();
        for (ChatConversation conversation : existing) {
            if (conversation.getClientId() != null) {
                if (conversation.getDeletedAt() != null) {
                    deletedExistingClientIds.add(conversation.getClientId());
                } else {
                    existingByClientId.putIfAbsent(conversation.getClientId(), conversation);
                }
            }
        }

        LocalDateTime now = LocalDateTime.now();
        for (String clientId : deletedClientIds) {
            ChatConversation conversation = existingByClientId.get(clientId);
            if (conversation != null && conversation.getDeletedAt() == null) {
                conversation.setDeletedAt(now);
                conversation.setUpdatedAt(now);
                conversationRepository.save(conversation);
            }
        }

        for (ChatConversationDto dto : incoming) {
            String clientId = normalizedClientId(dto.getClientId());
            if (clientId == null) {
                continue;
            }
            if (deletedClientIds.contains(clientId)
                    || deletedExistingClientIds.contains(clientId)
                    || !processedConversationIds.add(clientId)) {
                continue;
            }
            ChatConversation conversation = existingByClientId.getOrDefault(clientId, new ChatConversation());
            if (conversation.getDeletedAt() != null) {
                continue;
            }
            boolean isNewConversation = conversation.getId() == null;
            LocalDateTime incomingUpdatedAt = fromMillis(dto.getUpdatedAt());
            conversation.setUserId(user.getId());
            conversation.setUserEmail(authService.normalizeEmail(user.getEmail()));
            conversation.setClientId(clientId);
            if (isNewConversation || conversation.getCreatedAt() == null) {
                conversation.setCreatedAt(fromMillis(dto.getCreatedAt()));
            }
            if (isNewConversation || conversation.getUpdatedAt() == null
                    || !incomingUpdatedAt.isBefore(conversation.getUpdatedAt())) {
                conversation.setTitle(safeTitle(dto.getTitle()));
                conversation.setConversationType(resolveType(dto.getType()));
                conversation.setUpdatedAt(incomingUpdatedAt);
            }
            conversation = conversationRepository.save(conversation);

            upsertMessages(conversation.getId(), dto.getMessages(), user.getId());
        }

        ChatSyncResponse response = load(user);
        response.setActiveId(request.getActiveId());
        return response;
    }

    @Transactional
    public void softDelete(UserAccount user, String clientId) {
        String normalized = normalizedClientId(clientId);
        if (normalized == null) {
            return;
        }
        lockUserForWrite(user);
        List<ChatConversation> conversations = conversationRepository
                .findAllByUserIdAndClientId(user.getId(), normalized);
        if (conversations.isEmpty()) {
            ChatConversation tombstone = new ChatConversation();
            tombstone.setUserId(user.getId());
            tombstone.setUserEmail(authService.normalizeEmail(user.getEmail()));
            tombstone.setClientId(normalized);
            tombstone.setTitle("已删除对话");
            tombstone.setConversationType("text2img");
            tombstone.setDeletedAt(LocalDateTime.now());
            conversationRepository.save(tombstone);
            log.info("Chat deletion tombstone created: user={}, clientId={}",
                    user.getEmail(), normalized);
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        boolean changed = false;
        for (ChatConversation conversation : conversations) {
            if (conversation.getDeletedAt() == null) {
                conversation.setDeletedAt(now);
                changed = true;
            }
            conversation.setUpdatedAt(now);
        }
        if (changed) {
            conversationRepository.saveAll(conversations);
            log.info("Chat conversations soft deleted: user={}, clientId={}, count={}",
                    user.getEmail(), normalized, conversations.size());
        }
    }

    private ChatConversationDto toDto(ChatConversation conversation, List<ChatMessage> messages,
                                      Map<Integer, GenerationRecord> generationRecords,
                                      Map<String, GenerationRecord> generationRecordsByClientMessage,
                                      Map<String, VideoGenerationTask> videoTasksByCode,
                                      Map<String, VideoGenerationTask> videoTasksByClientMessage) {
        ChatConversationDto dto = new ChatConversationDto();
        dto.setClientId(conversation.getClientId());
        dto.setTitle(conversation.getTitle());
        dto.setType(conversation.getConversationType());
        dto.setCreatedAt(toMillis(conversation.getCreatedAt()));
        dto.setUpdatedAt(toMillis(conversation.getUpdatedAt()));
        dto.setMessages(messages.stream()
                .sorted(Comparator
                        .comparing(ChatMessage::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(ChatMessage::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(message -> {
                    GenerationRecord record = generationRecords.get(message.getGenerationRecordId());
                    if (record == null && message.getClientId() != null) {
                        record = generationRecordsByClientMessage.get(message.getClientId());
                    }
                    VideoGenerationTask videoTask = message.getVideoTaskCode() == null
                            ? null
                            : videoTasksByCode.get(message.getVideoTaskCode());
                    if (videoTask == null && message.getClientId() != null) {
                        videoTask = videoTasksByClientMessage.get(message.getClientId());
                    }
                    return toDto(message, record, videoTask);
                })
                .toList());
        return dto;
    }

    private ChatMessageDto toDto(ChatMessage message, GenerationRecord record, VideoGenerationTask videoTask) {
        ChatMessageDto dto = new ChatMessageDto();
        dto.setClientId(message.getClientId() != null ? message.getClientId() : String.valueOf(message.getId()));
        dto.setRole(message.getRole());
        dto.setContent(message.getContent());
        dto.setSourceImage(message.getSourceImageUrl() != null
                ? message.getSourceImageUrl()
                : message.getImagePath());
        dto.setRecordId(record != null ? record.getId() : message.getGenerationRecordId());
        dto.setVideoTaskCode(message.getVideoTaskCode());
        dto.setMetadata(message.getMetadata());
        if (videoTask != null) {
            VideoGenerationResponse response = videoTaskMapper.toResponse(videoTask);
            dto.setVideoTaskCode(videoTask.getTaskCode());
            dto.setVideoTask(response);
            dto.setStatus(response.status());
            if ("failed".equals(response.status())) {
                dto.setErrorMessage(response.errorMessage());
            }
        } else if (record != null) {
            dto.setRecordImagePath(recordViewService.resolveImagePath(record));
            dto.setRecordSourceImagePath(record.getSourceImagePath());
            dto.setRecordSourceImagePaths(record.getSourceImagePaths());
            dto.setRecordRemoteImageAvailable(record.isRemoteImageAvailable());
            dto.setRecordTaskCode(record.getTaskCode());
            dto.setRecordGenerationType(record.getGenerationType());
            dto.setRecordRecoverable(record.isRecoverable());
            dto.setStatus(record.getStatus());
            if (GenerationState.isFailed(record.getStatus())) {
                dto.setErrorMessage(record.getErrorMessage());
            }
        } else {
            dto.setStatus(message.getIntent());
            if ("assistant".equals(message.getRole()) && GenerationState.isFailed(message.getIntent())) {
                dto.setErrorMessage(message.getContent());
            }
        }
        dto.setCreatedAt(toMillis(message.getCreatedAt()));
        return dto;
    }

    private void upsertMessages(Integer conversationId, List<ChatMessageDto> dtos, Integer userId) {
        if (dtos == null || dtos.isEmpty()) {
            return;
        }
        Map<String, ChatMessage> existingByClientId = new HashMap<>();
        Map<Integer, ChatMessage> existingById = new HashMap<>();
        for (ChatMessage message : messageRepository.findByConversationId(conversationId)) {
            if (message.getId() != null) {
                existingById.put(message.getId(), message);
            }
            if (message.getClientId() != null && !message.getClientId().isBlank()) {
                existingByClientId.put(message.getClientId(), message);
            }
        }
        List<ChatMessage> messages = new ArrayList<>();
        Set<String> processedClientIds = new HashSet<>();
        for (ChatMessageDto dto : dtos) {
            String role = resolveRole(dto.getRole());
            if (role == null) {
                continue;
            }
            String clientId = normalizedClientId(dto.getClientId());
            if (clientId == null || !processedClientIds.add(clientId)) {
                continue;
            }
            ChatMessage message = resolveExistingMessage(clientId, existingByClientId, existingById);
            message.setConversationId(conversationId);
            message.setClientId(clientId);
            message.setRole(role);
            boolean statusRegression = isTerminalStatus(message.getIntent())
                    && !message.getIntent().equals(dto.getStatus());
            if (!statusRegression) {
                String incomingContent = resolveContent(dto, role);
                if (incomingContent != null && (!incomingContent.isBlank() || message.getContent() == null)) {
                    message.setContent(incomingContent);
                }
                if (dto.getStatus() != null && !dto.getStatus().isBlank()) {
                    message.setIntent(dto.getStatus());
                }
            }
            String sourceImageUrl = safeSourceImageUrl(dto.getSourceImage());
            String sourceImagePath = sourceImageUrl == null ? safeImagePath(dto.getSourceImage()) : null;
            if (sourceImageUrl != null) {
                message.setSourceImageUrl(sourceImageUrl);
            } else if (sourceImagePath != null) {
                message.setImagePath(sourceImagePath);
            }
            if (message.getGenerationRecordId() == null && dto.getRecordId() != null) {
                message.setGenerationRecordId(dto.getRecordId());
            }
            String videoTaskCode = safeVideoTaskCode(dto.getVideoTaskCode());
            if (message.getVideoTaskCode() == null && videoTaskCode != null
                    && videoTaskRepository.existsByTaskCodeAndUserId(videoTaskCode, userId)) {
                message.setVideoTaskCode(videoTaskCode);
            }
            if (dto.getMetadata() != null && dto.getMetadata().length() <= 20000) {
                message.setMetadata(dto.getMetadata());
            }
            if (message.getCreatedAt() == null) {
                message.setCreatedAt(fromMillis(dto.getCreatedAt()));
            }
            messages.add(message);
        }
        if (!messages.isEmpty()) {
            messageRepository.saveAll(messages);
        }
    }

    private ChatMessage resolveExistingMessage(String clientId, Map<String, ChatMessage> existingByClientId,
                                               Map<Integer, ChatMessage> existingById) {
        if (clientId != null) {
            ChatMessage byClientId = existingByClientId.get(clientId);
            if (byClientId != null) {
                return byClientId;
            }
            try {
                ChatMessage byLegacyId = existingById.get(Integer.parseInt(clientId));
                if (byLegacyId != null && byLegacyId.getClientId() == null) {
                    return byLegacyId;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return new ChatMessage();
    }

    private String resolveContent(ChatMessageDto dto, String role) {
        String content;
        if ("assistant".equals(role) && "failed".equals(dto.getStatus()) && dto.getErrorMessage() != null) {
            content = dto.getErrorMessage();
        } else {
            content = dto.getContent();
        }
        if (content == null || content.length() <= MAX_MESSAGE_CHARS) {
            return content;
        }
        return content.substring(0, MAX_MESSAGE_CHARS);
    }

    private boolean isTerminalStatus(String status) {
        return GenerationState.isCompleted(status) || GenerationState.isFailed(status);
    }

    private String normalizedClientId(String clientId) {
        if (clientId == null || clientId.isBlank()) {
            return null;
        }
        return clientId.trim();
    }

    private void lockUserForWrite(UserAccount user) {
        if (user == null || user.getId() == null) {
            throw new IllegalArgumentException("User is required for chat synchronization");
        }
        userRepository.findByIdForUpdate(user.getId())
                .orElseThrow(() -> new IllegalArgumentException("User no longer exists"));
    }

    private Set<String> normalizedClientIds(List<String> clientIds) {
        Set<String> values = new HashSet<>();
        if (clientIds == null) {
            return values;
        }
        for (String clientId : clientIds) {
            String normalized = normalizedClientId(clientId);
            if (normalized != null) {
                values.add(normalized);
            }
        }
        return values;
    }

    private String safeTitle(String title) {
        String value = title == null || title.isBlank() ? "新对话" : title.trim();
        return value.length() > 255 ? value.substring(0, 255) : value;
    }

    private String resolveType(String type) {
        if ("img2img".equals(type) || "text2video".equals(type) || "image2video".equals(type)
                || "prompt_assistant".equals(type)) {
            return type;
        }
        return "text2img";
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
        if (imagePath.regionMatches(true, 0, "http://", 0, 7)
                || imagePath.regionMatches(true, 0, "https://", 0, 8)) {
            return null;
        }
        return imagePath.length() > 500 ? null : imagePath;
    }

    private String safeSourceImageUrl(String sourceImage) {
        if (sourceImage == null || sourceImage.isBlank()) {
            return null;
        }
        String value = sourceImage.trim();
        if (value.length() > 4000) {
            return null;
        }
        try {
            URI uri = URI.create(value);
            boolean http = "http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme());
            return http && uri.getHost() != null ? value : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private String safeVideoTaskCode(String taskCode) {
        if (taskCode == null || taskCode.isBlank()) {
            return null;
        }
        String value = taskCode.trim();
        return value.length() <= 40 && value.matches("[A-Za-z0-9_-]+") ? value : null;
    }

    private LocalDateTime fromMillis(Long millis) {
        long now = System.currentTimeMillis();
        if (millis == null || millis <= 0 || millis > now + MAX_CLOCK_SKEW_MILLIS) {
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
