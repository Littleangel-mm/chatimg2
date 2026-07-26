package com.example.chatimg2.controller;

import com.example.chatimg2.dto.ApiResponse;
import com.example.chatimg2.dto.ChatSyncRequest;
import com.example.chatimg2.dto.ChatSyncResponse;
import com.example.chatimg2.service.AuthPrincipal;
import com.example.chatimg2.service.AuthService;
import com.example.chatimg2.service.ChatHistoryService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final AuthService authService;
    private final ChatHistoryService chatHistoryService;

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
}
