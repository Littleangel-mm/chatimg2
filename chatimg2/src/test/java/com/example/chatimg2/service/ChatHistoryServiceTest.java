package com.example.chatimg2.service;

import com.example.chatimg2.dto.ChatConversationDto;
import com.example.chatimg2.dto.ChatMessageDto;
import com.example.chatimg2.dto.ChatSyncRequest;
import com.example.chatimg2.entity.ChatConversation;
import com.example.chatimg2.entity.ChatMessage;
import com.example.chatimg2.entity.GenerationRecord;
import com.example.chatimg2.entity.UserAccount;
import com.example.chatimg2.repository.ChatConversationRepository;
import com.example.chatimg2.repository.ChatMessageRepository;
import com.example.chatimg2.repository.GenerationRecordRepository;
import com.example.chatimg2.repository.UserAccountRepository;
import com.example.chatseedance2.repository.VideoGenerationTaskRepository;
import com.example.chatseedance2.service.VideoTaskMapper;
import com.example.chatseedance2.dto.VideoGenerationResponse;
import com.example.chatseedance2.entity.VideoGenerationTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatHistoryServiceTest {

    @Mock
    private ChatConversationRepository conversationRepository;

    @Mock
    private ChatMessageRepository messageRepository;

    @Mock
    private GenerationRecordRepository generationRecordRepository;

    @Mock
    private UserAccountRepository userRepository;

    @Mock
    private GenerationRecordViewService recordViewService;

    @Mock
    private AuthService authService;

    @Mock
    private VideoGenerationTaskRepository videoTaskRepository;

    @Mock
    private VideoTaskMapper videoTaskMapper;

    @InjectMocks
    private ChatHistoryService chatHistoryService;

    @Test
    void softDeleteMarksOnlyTheCurrentUsersConversation() {
        UserAccount user = user(7, "user@qq.com");
        ChatConversation conversation = conversation(31, 7, "client-1");
        when(conversationRepository.findByUserIdAndClientId(7, "client-1"))
                .thenReturn(Optional.of(conversation));
        when(userRepository.findByIdForUpdate(7)).thenReturn(Optional.of(user));

        chatHistoryService.softDelete(user, "client-1");

        assertNotNull(conversation.getDeletedAt());
        verify(conversationRepository).save(conversation);
    }

    @Test
    void softDeleteIsIdempotent() {
        UserAccount user = user(7, "user@qq.com");
        ChatConversation conversation = conversation(31, 7, "client-1");
        conversation.setDeletedAt(LocalDateTime.now().minusMinutes(1));
        when(conversationRepository.findByUserIdAndClientId(7, "client-1"))
                .thenReturn(Optional.of(conversation));
        when(userRepository.findByIdForUpdate(7)).thenReturn(Optional.of(user));

        chatHistoryService.softDelete(user, "client-1");

        verify(conversationRepository, never()).save(conversation);
    }

    @Test
    void softDeleteDoesNotTouchAnotherUsersConversation() {
        UserAccount user = user(7, "user@qq.com");
        when(conversationRepository.findByUserIdAndClientId(7, "other-client"))
                .thenReturn(Optional.empty());
        when(userRepository.findByIdForUpdate(7)).thenReturn(Optional.of(user));

        chatHistoryService.softDelete(user, "other-client");

        verify(conversationRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void staleDeviceCannotRegressCompletedMessage() {
        ChatMessage existing = message("assistant-1", "completed", 91, "sources/user/reference.png");
        existing.setContent("completed result");
        when(messageRepository.findByConversationId(31)).thenReturn(List.of(existing));

        ChatMessageDto stale = messageDto("assistant-1", "processing", null, null);
        stale.setContent("");
        ReflectionTestUtils.invokeMethod(chatHistoryService, "upsertMessages", 31, List.of(stale), 7);

        assertEquals("completed", existing.getIntent());
        assertEquals(91, existing.getGenerationRecordId());
        assertEquals("sources/user/reference.png", existing.getImagePath());
        assertEquals("completed result", existing.getContent());
        verify(messageRepository).saveAll(org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void completedMessageCanAdvanceProcessingMessage() {
        ChatMessage existing = message("assistant-1", "processing", null, null);
        when(messageRepository.findByConversationId(31)).thenReturn(List.of(existing));

        ChatMessageDto completed = messageDto("assistant-1", "completed", 92, "sources/user/reference.png");
        ReflectionTestUtils.invokeMethod(chatHistoryService, "upsertMessages", 31, List.of(completed), 7);

        assertEquals("completed", existing.getIntent());
        assertEquals(92, existing.getGenerationRecordId());
        assertEquals("sources/user/reference.png", existing.getImagePath());
    }

    @Test
    void terminalFailedMessageCannotBeRevivedByStaleClient() {
        ChatMessage existing = message("assistant-1", "failed", 92, null);
        existing.setContent("generation failed");
        when(messageRepository.findByConversationId(31)).thenReturn(List.of(existing));

        ChatMessageDto stale = messageDto("assistant-1", "completed", 93, null);
        stale.setContent("stale completed result");
        ReflectionTestUtils.invokeMethod(chatHistoryService, "upsertMessages", 31, List.of(stale), 7);

        assertEquals("failed", existing.getIntent());
        assertEquals(92, existing.getGenerationRecordId());
        assertEquals("generation failed", existing.getContent());
    }

    @Test
    void duplicateMessageIdsInOneSyncAreStoredOnce() {
        when(messageRepository.findByConversationId(31)).thenReturn(List.of());
        ChatMessageDto first = messageDto("assistant-1", "processing", null, null);
        ChatMessageDto duplicate = messageDto("assistant-1", "processing", null, null);

        ReflectionTestUtils.invokeMethod(chatHistoryService, "upsertMessages", 31, List.of(first, duplicate), 7);

        verify(messageRepository).saveAll(org.mockito.ArgumentMatchers.argThat(messages ->
                java.util.stream.StreamSupport.stream(messages.spliterator(), false).count() == 1));
    }

    @Test
    void staleDeviceCannotOverwriteNewerConversationMetadata() {
        UserAccount user = user(7, "user@qq.com");
        ChatConversation existing = conversation(31, 7, "client-1");
        existing.setTitle("new title");
        existing.setConversationType("img2img");
        existing.setCreatedAt(LocalDateTime.now().minusHours(1));
        existing.setUpdatedAt(LocalDateTime.now());

        ChatConversationDto stale = new ChatConversationDto();
        stale.setClientId("client-1");
        stale.setTitle("old title");
        stale.setType("text2img");
        stale.setCreatedAt(System.currentTimeMillis() - 3_600_000L);
        stale.setUpdatedAt(System.currentTimeMillis() - 60_000L);
        ChatSyncRequest request = new ChatSyncRequest();
        request.setConversations(List.of(stale));

        when(conversationRepository.findByUserIdOrderByUpdatedAtDesc(7)).thenReturn(List.of(existing));
        when(userRepository.findByIdForUpdate(7)).thenReturn(Optional.of(user));
        when(conversationRepository.save(org.mockito.ArgumentMatchers.any(ChatConversation.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(conversationRepository.findByUserIdAndDeletedAtIsNullOrderByUpdatedAtDesc(7))
                .thenReturn(List.of(existing));
        when(messageRepository.findByConversationIdInOrderByCreatedAtAscIdAsc(List.of(31)))
                .thenReturn(List.of());
        when(authService.normalizeEmail("user@qq.com")).thenReturn("user@qq.com");

        chatHistoryService.sync(user, request);

        assertEquals("new title", existing.getTitle());
        assertEquals("img2img", existing.getConversationType());
    }

    @Test
    void loadIncludesSafeGenerationSummaryForOtherDevices() {
        UserAccount user = user(7, "user@qq.com");
        ChatConversation conversation = conversation(31, 7, "client-1");
        conversation.setCreatedAt(LocalDateTime.now().minusMinutes(2));
        conversation.setUpdatedAt(LocalDateTime.now());
        ChatMessage message = message("assistant-1", GenerationState.PROCESSING, 99, null);
        GenerationRecord record = new GenerationRecord();
        record.setId(99);
        record.setStatus(GenerationState.COMPLETED);
        record.setRemoteImageUrl("https://cdn.example/signed-result");
        record.setTaskCode("TASK-99");
        record.setGenerationType("img2img");
        record.setImagePath("TASK-99.png");
        record.setSourceImagePath("sources/user@qq.com/reference.png");

        when(conversationRepository.findByUserIdAndDeletedAtIsNullOrderByUpdatedAtDesc(7))
                .thenReturn(List.of(conversation));
        when(messageRepository.findByConversationIdInOrderByCreatedAtAscIdAsc(List.of(31)))
                .thenReturn(List.of(message));
        when(generationRecordRepository.findAllById(List.of(99))).thenReturn(List.of(record));
        when(recordViewService.resolveImagePath(record)).thenReturn("user@qq.com/TASK-99.png");

        var response = chatHistoryService.load(user);
        ChatMessageDto dto = response.getConversations().get(0).getMessages().get(0);

        assertEquals(99, dto.getRecordId());
        assertEquals(GenerationState.COMPLETED, dto.getStatus());
        assertEquals("user@qq.com/TASK-99.png", dto.getRecordImagePath());
        assertEquals("TASK-99", dto.getRecordTaskCode());
        assertEquals("img2img", dto.getRecordGenerationType());
        assertEquals("sources/user@qq.com/reference.png", dto.getRecordSourceImagePath());
        assertTrue(dto.getRecordRemoteImageAvailable());
    }

    @Test
    void loadAssociatesGenerationByAssistantClientIdAfterDeviceDisconnect() {
        UserAccount user = user(7, "user@qq.com");
        ChatConversation conversation = conversation(31, 7, "client-1");
        conversation.setCreatedAt(LocalDateTime.now().minusMinutes(2));
        conversation.setUpdatedAt(LocalDateTime.now());
        ChatMessage message = message("assistant-1", GenerationState.PROCESSING, null, null);
        GenerationRecord record = new GenerationRecord();
        record.setId(101);
        record.setClientMessageId("assistant-1");
        record.setStatus(GenerationState.COMPLETED);
        record.setImagePath("user@qq.com/TASK-101.png");
        record.setCreatedAt(LocalDateTime.now());

        when(conversationRepository.findByUserIdAndDeletedAtIsNullOrderByUpdatedAtDesc(7))
                .thenReturn(List.of(conversation));
        when(messageRepository.findByConversationIdInOrderByCreatedAtAscIdAsc(List.of(31)))
                .thenReturn(List.of(message));
        when(generationRecordRepository
                .findByUserIdAndClientMessageIdInOrderByCreatedAtDesc(7, List.of("assistant-1")))
                .thenReturn(List.of(record));
        when(recordViewService.resolveImagePath(record)).thenReturn("user@qq.com/TASK-101.png");

        ChatMessageDto dto = chatHistoryService.load(user)
                .getConversations().get(0).getMessages().get(0);

        assertEquals(101, dto.getRecordId());
        assertEquals(GenerationState.COMPLETED, dto.getStatus());
        assertEquals("user@qq.com/TASK-101.png", dto.getRecordImagePath());
    }

    @Test
    void loadAttachesOwnedVideoTaskByStoredTaskCode() {
        UserAccount user = user(7, "user@qq.com");
        ChatConversation conversation = conversation(31, 7, "video-conversation");
        conversation.setCreatedAt(LocalDateTime.now().minusMinutes(2));
        conversation.setUpdatedAt(LocalDateTime.now());
        ChatMessage message = message("assistant-video-1", "processing", null, null);
        message.setVideoTaskCode("VIDEO-101");
        VideoGenerationTask task = videoTask(101L, 7, "VIDEO-101", "assistant-video-1");
        VideoGenerationResponse mapped = videoResponse("VIDEO-101", "completed");

        when(conversationRepository.findByUserIdAndDeletedAtIsNullOrderByUpdatedAtDesc(7))
                .thenReturn(List.of(conversation));
        when(messageRepository.findByConversationIdInOrderByCreatedAtAscIdAsc(List.of(31)))
                .thenReturn(List.of(message));
        when(videoTaskRepository.findByTaskCodeIn(List.of("VIDEO-101"))).thenReturn(List.of(task));
        when(videoTaskMapper.toResponse(task)).thenReturn(mapped);

        ChatMessageDto dto = chatHistoryService.load(user)
                .getConversations().get(0).getMessages().get(0);

        assertEquals("VIDEO-101", dto.getVideoTaskCode());
        assertEquals(mapped, dto.getVideoTask());
        assertEquals("completed", dto.getStatus());
    }

    @Test
    void loadRecoversOwnedVideoTaskByAssistantClientId() {
        UserAccount user = user(7, "user@qq.com");
        ChatConversation conversation = conversation(31, 7, "video-conversation");
        conversation.setCreatedAt(LocalDateTime.now().minusMinutes(2));
        conversation.setUpdatedAt(LocalDateTime.now());
        ChatMessage message = message("assistant-video-2", "processing", null, null);
        VideoGenerationTask task = videoTask(102L, 7, "VIDEO-102", "assistant-video-2");
        VideoGenerationResponse mapped = videoResponse("VIDEO-102", "processing");

        when(conversationRepository.findByUserIdAndDeletedAtIsNullOrderByUpdatedAtDesc(7))
                .thenReturn(List.of(conversation));
        when(messageRepository.findByConversationIdInOrderByCreatedAtAscIdAsc(List.of(31)))
                .thenReturn(List.of(message));
        when(videoTaskRepository.findByUserIdAndClientMessageIdInOrderByCreatedAtDesc(
                7, List.of("assistant-video-2"))).thenReturn(List.of(task));
        when(videoTaskMapper.toResponse(task)).thenReturn(mapped);

        ChatMessageDto dto = chatHistoryService.load(user)
                .getConversations().get(0).getMessages().get(0);

        assertEquals("VIDEO-102", dto.getVideoTaskCode());
        assertEquals(mapped, dto.getVideoTask());
    }

    @Test
    void loadNeverExposesAnotherUsersVideoTask() {
        UserAccount user = user(7, "user@qq.com");
        ChatConversation conversation = conversation(31, 7, "video-conversation");
        conversation.setCreatedAt(LocalDateTime.now().minusMinutes(2));
        conversation.setUpdatedAt(LocalDateTime.now());
        ChatMessage message = message("assistant-video-3", "processing", null, null);
        message.setVideoTaskCode("VIDEO-OTHER");
        VideoGenerationTask otherUsersTask = videoTask(103L, 8, "VIDEO-OTHER", "assistant-video-3");

        when(conversationRepository.findByUserIdAndDeletedAtIsNullOrderByUpdatedAtDesc(7))
                .thenReturn(List.of(conversation));
        when(messageRepository.findByConversationIdInOrderByCreatedAtAscIdAsc(List.of(31)))
                .thenReturn(List.of(message));
        when(videoTaskRepository.findByTaskCodeIn(List.of("VIDEO-OTHER")))
                .thenReturn(List.of(otherUsersTask));

        ChatMessageDto dto = chatHistoryService.load(user)
                .getConversations().get(0).getMessages().get(0);

        assertNull(dto.getVideoTask());
        verify(videoTaskMapper, never()).toResponse(otherUsersTask);
    }

    @Test
    void syncDoesNotStoreAnotherUsersVideoTaskCode() {
        when(messageRepository.findByConversationId(31)).thenReturn(List.of());
        ChatMessageDto incoming = messageDto("assistant-video-4", "processing", null, null);
        incoming.setVideoTaskCode("VIDEO-OTHER");
        when(videoTaskRepository.existsByTaskCodeAndUserId("VIDEO-OTHER", 7)).thenReturn(false);

        ReflectionTestUtils.invokeMethod(chatHistoryService, "upsertMessages", 31, List.of(incoming), 7);

        verify(messageRepository).saveAll(org.mockito.ArgumentMatchers.argThat(messages -> {
            ChatMessage saved = messages.iterator().next();
            return saved.getVideoTaskCode() == null;
        }));
    }

    @Test
    void syncStoresExternalReferenceInDedicatedUrlColumn() {
        when(messageRepository.findByConversationId(31)).thenReturn(List.of());
        ChatMessageDto incoming = new ChatMessageDto();
        incoming.setClientId("user-video-url");
        incoming.setRole("user");
        incoming.setContent("animate this image");
        incoming.setSourceImage("https://images.example.com/public/reference.png?token=abc");
        incoming.setCreatedAt(System.currentTimeMillis());

        ReflectionTestUtils.invokeMethod(chatHistoryService, "upsertMessages", 31, List.of(incoming), 7);

        verify(messageRepository).saveAll(org.mockito.ArgumentMatchers.argThat(messages -> {
            ChatMessage saved = messages.iterator().next();
            return "https://images.example.com/public/reference.png?token=abc"
                    .equals(saved.getSourceImageUrl()) && saved.getImagePath() == null;
        }));
    }

    @Test
    void loadReturnsExternalReferenceUrlForConversationRefresh() {
        UserAccount user = user(7, "user@qq.com");
        ChatConversation conversation = conversation(31, 7, "video-url-conversation");
        conversation.setCreatedAt(LocalDateTime.now().minusMinutes(2));
        conversation.setUpdatedAt(LocalDateTime.now());
        ChatMessage message = message("user-video-url", null, null, "legacy/local.png");
        message.setRole("user");
        message.setSourceImageUrl("https://images.example.com/public/reference.png");

        when(conversationRepository.findByUserIdAndDeletedAtIsNullOrderByUpdatedAtDesc(7))
                .thenReturn(List.of(conversation));
        when(messageRepository.findByConversationIdInOrderByCreatedAtAscIdAsc(List.of(31)))
                .thenReturn(List.of(message));

        ChatMessageDto dto = chatHistoryService.load(user)
                .getConversations().get(0).getMessages().get(0);

        assertEquals("https://images.example.com/public/reference.png", dto.getSourceImage());
    }

    @Test
    void loadIncludesDeletionTombstonesForOtherDevices() {
        UserAccount user = user(7, "user@qq.com");
        ChatConversation deleted = conversation(32, 7, "deleted-client");
        deleted.setDeletedAt(LocalDateTime.now());
        deleted.setUpdatedAt(LocalDateTime.now());

        when(conversationRepository.findByUserIdAndDeletedAtIsNullOrderByUpdatedAtDesc(7))
                .thenReturn(List.of());
        when(conversationRepository.findDeletedClientIdsByUserId(7))
                .thenReturn(List.of(deleted.getClientId()));

        var response = chatHistoryService.load(user);

        assertEquals(List.of("deleted-client"), response.getDeletedClientIds());
        assertTrue(response.getConversations().isEmpty());
    }

    private UserAccount user(int id, String email) {
        UserAccount user = new UserAccount();
        user.setId(id);
        user.setEmail(email);
        return user;
    }

    private ChatConversation conversation(int id, int userId, String clientId) {
        ChatConversation conversation = new ChatConversation();
        conversation.setId(id);
        conversation.setUserId(userId);
        conversation.setClientId(clientId);
        return conversation;
    }

    private ChatMessage message(String clientId, String status, Integer recordId, String imagePath) {
        ChatMessage message = new ChatMessage();
        message.setId(41);
        message.setConversationId(31);
        message.setClientId(clientId);
        message.setRole("assistant");
        message.setIntent(status);
        message.setGenerationRecordId(recordId);
        message.setImagePath(imagePath);
        message.setCreatedAt(LocalDateTime.now());
        return message;
    }

    private ChatMessageDto messageDto(String clientId, String status, Integer recordId, String sourceImage) {
        ChatMessageDto dto = new ChatMessageDto();
        dto.setClientId(clientId);
        dto.setRole("assistant");
        dto.setStatus(status);
        dto.setRecordId(recordId);
        dto.setSourceImage(sourceImage);
        dto.setCreatedAt(System.currentTimeMillis());
        return dto;
    }

    private VideoGenerationTask videoTask(long id, int userId, String taskCode, String clientMessageId) {
        VideoGenerationTask task = new VideoGenerationTask();
        task.setId(id);
        task.setUserId(userId);
        task.setTaskCode(taskCode);
        task.setClientMessageId(clientMessageId);
        return task;
    }

    private VideoGenerationResponse videoResponse(String taskCode, String status) {
        return new VideoGenerationResponse(
                 taskCode, "assistant-video", status, status, !"completed".equals(status), "ty-sd2-mini-15s",
                 "text_to_video", "prompt", 5, "16:9", "720p", 10, null,
                 List.of(), List.of("/video-media/results/result.mp4"), null, null, null,
                LocalDateTime.now(), "completed".equals(status) ? LocalDateTime.now() : null);
    }
}
