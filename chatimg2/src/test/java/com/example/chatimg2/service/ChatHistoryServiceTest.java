package com.example.chatimg2.service;

import com.example.chatimg2.entity.ChatConversation;
import com.example.chatimg2.entity.UserAccount;
import com.example.chatimg2.repository.ChatConversationRepository;
import com.example.chatimg2.repository.ChatMessageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    private AuthService authService;

    @InjectMocks
    private ChatHistoryService chatHistoryService;

    @Test
    void softDeleteMarksOnlyTheCurrentUsersConversation() {
        UserAccount user = user(7, "user@qq.com");
        ChatConversation conversation = conversation(31, 7, "client-1");
        when(conversationRepository.findByUserIdAndClientId(7, "client-1"))
                .thenReturn(Optional.of(conversation));

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

        chatHistoryService.softDelete(user, "client-1");

        verify(conversationRepository, never()).save(conversation);
    }

    @Test
    void softDeleteDoesNotTouchAnotherUsersConversation() {
        UserAccount user = user(7, "user@qq.com");
        when(conversationRepository.findByUserIdAndClientId(7, "other-client"))
                .thenReturn(Optional.empty());

        chatHistoryService.softDelete(user, "other-client");

        verify(conversationRepository, never()).save(org.mockito.ArgumentMatchers.any());
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
}
