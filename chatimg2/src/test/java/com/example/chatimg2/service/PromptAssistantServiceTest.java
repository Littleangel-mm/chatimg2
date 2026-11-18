package com.example.chatimg2.service;

import com.example.chatimg2.dto.AssistantChatRequest;
import com.example.chatimg2.entity.ChatConversation;
import com.example.chatimg2.entity.ChatMessage;
import com.example.chatimg2.entity.UserAccount;
import com.example.chatimg2.repository.ChatConversationRepository;
import com.example.chatimg2.repository.ChatMessageRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.net.InetSocketAddress;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PromptAssistantServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<ChatMessage> messages = new ArrayList<>();
    private final AtomicInteger calls = new AtomicInteger();
    private final ChatConversationRepository conversations = mock(ChatConversationRepository.class);
    private final ChatMessageRepository messageRepository = mock(ChatMessageRepository.class);
    private PromptAssistantService service;
    private HttpServer server;
    private JsonNode submitted;
    private int upstreamStatus = 200;
    private UserAccount user;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            calls.incrementAndGet();
            submitted = mapper.readTree(exchange.getRequestBody());
            String answer = mapper.writeValueAsString(java.util.Map.of(
                    "reply", "可以，保留人物并把背景改为海边。", "prompt", "Same person on a beach",
                    "negativePrompt", "blur", "questions", List.of(), "suggestions", List.of()));
            byte[] body = mapper.writeValueAsBytes(java.util.Map.of("choices", List.of(
                    java.util.Map.of("message", java.util.Map.of("content", answer)))));
            exchange.sendResponseHeaders(upstreamStatus, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        service = new PromptAssistantService(conversations, messageRepository);
        ReflectionTestUtils.setField(service, "baseUrl", "http://127.0.0.1:" + server.getAddress().getPort());
        ReflectionTestUtils.setField(service, "apiKey", "test-key");
        ReflectionTestUtils.setField(service, "model", "deepseek-chat");
        ReflectionTestUtils.setField(service, "connectTimeoutMs", 1000L);
        ReflectionTestUtils.setField(service, "readTimeoutMs", 1000L);
        user = new UserAccount();
        user.setId(7);
        user.setEmail("test@qq.com");
        ChatConversation conversation = new ChatConversation();
        conversation.setId(31);
        conversation.setClientId("conversation-1");
        conversation.setUserId(7);
        when(conversations.findFirstByUserIdAndClientIdAndDeletedAtIsNull(7, "conversation-1"))
                .thenReturn(Optional.of(conversation));
        when(conversations.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(messageRepository.findByConversationId(31)).thenAnswer(invocation -> new ArrayList<>(messages));
        when(messageRepository.save(any())).thenAnswer(invocation -> {
            ChatMessage message = invocation.getArgument(0);
            if (!messages.contains(message)) messages.add(message);
            if (message.getCreatedAt() == null) message.setCreatedAt(LocalDateTime.now());
            if (message.getId() == null) message.setId(messages.size());
            return message;
        });
    }

    @AfterEach
    void tearDown() { server.stop(0); }

    @Test
    void pendingPlaceholderMustCallProviderAndUpdateTheSameReply() {
        add("user-1", "user", "把背景改为海边", null);
        add("assistant-user-1", "assistant", "", "processing");

        var response = service.chat(user, request("user-1"));

        assertEquals(1, calls.get(), "pending placeholder is not a cached answer");
        assertEquals("可以，保留人物并把背景改为海边。", response.getReply());
        assertEquals("assistant-user-1", response.getAssistantMessageId());
        assertEquals(2, messages.size(), "reuse placeholder instead of inserting a second reply");
        assertEquals("completed", messages.get(1).getIntent());
        assertNotNull(messages.get(1).getMetadata());
        assertEquals(2, submitted.path("messages").size(), "exclude pending placeholder and duplicate current user input");

        var retried = service.chat(user, request("user-1"));
        assertEquals(response.getReply(), retried.getReply());
        assertEquals(1, calls.get(), "retry must reuse the exact completed answer");
    }

    @Test
    void anotherTurnsCompletedReplyMustNotSatisfyThisRequest() {
        add("user-1", "user", "第一轮", null);
        add("assistant-user-1", "assistant", "", "processing");
        add("user-2", "user", "第二轮", null);
        ChatMessage other = add("assistant-user-2", "assistant", "其他问题的回答", "completed");
        other.setMetadata("{\"prompt\":\"Old complete prompt\"}");

        assertEquals("Same person on a beach", service.chat(user, request("user-1")).getPrompt());
        assertEquals(1, calls.get());
    }

    @Test
    void providerFailureMustPersistFailedStatusInsteadOfLeavingSpinner() {
        upstreamStatus = 503;
        add("user-1", "user", "把背景改为海边", null);
        ChatMessage pending = add("assistant-user-1", "assistant", "", "processing");

        assertThrows(ResponseStatusException.class, () -> service.chat(user, request("user-1")));
        assertEquals("failed", pending.getIntent());
        assertTrue(pending.getContent().contains("助手"));

        upstreamStatus = 200;
        assertEquals("Same person on a beach", service.chat(user, request("user-1")).getPrompt());
        assertEquals("completed", pending.getIntent());
        assertEquals(2, messages.size(), "failed reply can be retried without duplicating messages");
    }

    @Test
    void explicitClientReplyIdMustSurviveHistorySync() {
        add("user-1", "user", "把背景改为海边", null);
        ChatMessage pending = add("random-client-placeholder", "assistant", "", "processing");
        AssistantChatRequest request = request("user-1");
        request.setAssistantMessageId(pending.getClientId());

        var response = service.chat(user, request);

        assertEquals("random-client-placeholder", response.getAssistantMessageId());
        assertEquals(2, messages.size());
        assertEquals("completed", pending.getIntent());
    }

    @Test
    void legacyRandomPlaceholderAndEmptyCompletedReplyAreRegenerated() {
        add("user-1", "user", "把背景改为海边", null);
        ChatMessage pending = add("legacy-placeholder", "assistant", "", "completed");

        var response = service.chat(user, request("user-1"));

        assertEquals(1, calls.get());
        assertEquals("legacy-placeholder", response.getAssistantMessageId());
        assertEquals(response.getReply(), pending.getContent());
        assertEquals(2, messages.size());
    }

    @Test
    void nextTurnReceivesPreviousPromptAndCurrentInputExactlyOnce() {
        add("user-0", "user", "做一张人像", null);
        ChatMessage previous = add("assistant-user-0", "assistant", "先保留白色服装", "completed");
        previous.setMetadata("{\"kind\":\"prompt_assistant\",\"prompt\":\"Portrait in white clothes\"}");
        add("user-1", "user", "把背景改为海边", null);
        add("assistant-user-1", "assistant", "", "processing");

        service.chat(user, request("user-1"));

        assertEquals(4, submitted.path("messages").size());
        assertTrue(submitted.path("messages").get(2).path("content").asText().contains("Portrait in white clothes"));
        assertEquals("把背景改为海边", submitted.path("messages").get(3).path("content").asText());
    }

    @Test
    void deletedConversationMustNotBeRecreated() {
        when(conversations.findFirstByUserIdAndClientIdAndDeletedAtIsNull(7, "conversation-1"))
                .thenReturn(Optional.empty());
        when(conversations.findDeletedClientIdsByUserId(7)).thenReturn(List.of("conversation-1"));

        assertThrows(ResponseStatusException.class, () -> service.chat(user, request("user-1")));
        verify(conversations, never()).save(any());
        assertEquals(0, calls.get());
    }

    @Test
    @EnabledIfSystemProperty(named = "assistant.live", matches = "true")
    void configuredProviderReturnsARealReply() {
        // Explicit opt-in only; regular tests never read credentials or call a paid API.
        Dotenv env = Dotenv.configure().directory("src/env").ignoreIfMalformed().load();
        ReflectionTestUtils.setField(service, "apiKey", env.get("DEEPSEEK_API_KEY"));
        ReflectionTestUtils.setField(service, "baseUrl", env.get("DEEPSEEK_BASE_URL", "https://api.deepseek.com"));
        ReflectionTestUtils.setField(service, "model", env.get("DEEPSEEK_MODEL", "deepseek-chat"));
        ReflectionTestUtils.setField(service, "connectTimeoutMs", 10000L);
        ReflectionTestUtils.setField(service, "readTimeoutMs", 35000L);
        AssistantChatRequest request = request("user-live-probe");
        request.setMessage("请用一句中文告诉我，你能帮我优化画面提示词吗？暂时不需要给出提示词。");

        var response = service.chat(user, request);

        assertNotNull(response.getReply());
        assertFalse(response.getReply().isBlank());
        assertEquals("completed", messages.get(messages.size() - 1).getIntent());
        System.out.println("DeepSeek live Java verification: completed reply received and saved");
    }

    private AssistantChatRequest request(String id) {
        AssistantChatRequest request = new AssistantChatRequest();
        request.setConversationId("conversation-1");
        request.setClientMessageId(id);
        request.setMessage("把背景改为海边");
        return request;
    }

    private ChatMessage add(String id, String role, String content, String status) {
        ChatMessage message = new ChatMessage();
        message.setId(messages.size() + 1);
        message.setConversationId(31);
        message.setClientId(id);
        message.setRole(role);
        message.setContent(content);
        message.setIntent(status);
        message.setCreatedAt(LocalDateTime.now().minusSeconds(10).plusNanos(messages.size()));
        messages.add(message);
        return message;
    }
}
