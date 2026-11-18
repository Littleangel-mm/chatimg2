package com.example.chatimg2.controller;

import com.example.chatimg2.service.AuthPrincipal;
import com.example.chatimg2.service.AuthService;
import com.example.chatimg2.service.ChatHistoryService;
import com.example.chatimg2.service.PromptAssistantService;
import com.example.chatimg2.entity.UserAccount;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AssistantChatControllerTest {
    @Test
    void upstreamFailureReturnsSpecificStatusAndMessage() throws Exception {
        AuthService auth = mock(AuthService.class);
        PromptAssistantService service = mock(PromptAssistantService.class);
        UserAccount user = new UserAccount();
        user.setId(7);
        when(auth.requireUser(any())).thenReturn(new AuthPrincipal("USER", user, null));
        when(service.chat(eq(user), any())).thenThrow(
                new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "提示词助手尚未配置"));
        var mvc = MockMvcBuilders.standaloneSetup(
                new ChatController(auth, mock(ChatHistoryService.class), service)).build();

        mvc.perform(post("/api/chat/assistant/messages").contentType("application/json")
                        .content("{\"conversationId\":\"conversation-1\",\"clientMessageId\":\"user-1\",\"message\":\"hello\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(503))
                .andExpect(jsonPath("$.message").value("提示词助手尚未配置"));
    }
}
