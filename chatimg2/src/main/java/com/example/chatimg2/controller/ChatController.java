package com.example.chatimg2.controller;

import com.example.chatimg2.dto.ApiResponse;
import com.example.chatimg2.dto.AssistantChatRequest;
import com.example.chatimg2.dto.AssistantChatResponse;
import com.example.chatimg2.dto.ChatSyncRequest;
import com.example.chatimg2.dto.ChatSyncResponse;
import com.example.chatimg2.service.AuthPrincipal;
import com.example.chatimg2.service.AuthService;
import com.example.chatimg2.service.ChatHistoryService;
import com.example.chatimg2.service.PromptAssistantService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final AuthService authService;
    private final ChatHistoryService chatHistoryService;
    private final PromptAssistantService promptAssistantService;

    @GetMapping("/sync")
    public ApiResponse<ChatSyncResponse> load(HttpServletRequest request) {
        AuthPrincipal principal = authService.requireUser(request);
        return ApiResponse.success(chatHistoryService.load(principal.user()));
    }

    @PostMapping("/sync")
    public ApiResponse<ChatSyncResponse> sync(@RequestBody ChatSyncRequest syncRequest,
                                              HttpServletRequest request) {
        AuthPrincipal principal = authService.requireUser(request);
        return ApiResponse.success(chatHistoryService.sync(principal.user(), syncRequest));
    }

    @DeleteMapping("/conversations/{clientId}")
    public ApiResponse<Void> deleteConversation(@PathVariable String clientId,
                                                HttpServletRequest request) {
        AuthPrincipal principal = authService.requireUser(request);
        chatHistoryService.softDelete(principal.user(), clientId);
        return ApiResponse.success(null);
    }

    @PostMapping("/assistant/messages")
    public ApiResponse<AssistantChatResponse> assistant(@RequestBody AssistantChatRequest assistantRequest,
                                                         HttpServletRequest request) {
        AuthPrincipal principal = authService.requireUser(request);
        return ApiResponse.success(promptAssistantService.chat(principal.user(), assistantRequest));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiResponse<Void>> assistantError(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode())
                .body(ApiResponse.error(error.getStatusCode().value(), error.getReason()));
    }
}
