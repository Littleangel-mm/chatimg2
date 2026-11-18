package com.example.chatimg2.service;

import com.example.chatimg2.dto.AssistantChatRequest;
import com.example.chatimg2.dto.AssistantChatResponse;
import com.example.chatimg2.entity.ChatConversation;
import com.example.chatimg2.entity.ChatMessage;
import com.example.chatimg2.entity.UserAccount;
import com.example.chatimg2.repository.ChatConversationRepository;
import com.example.chatimg2.repository.ChatMessageRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Semaphore;
import java.util.stream.IntStream;
import java.util.Comparator;

@Slf4j
@Service
@RequiredArgsConstructor
public class PromptAssistantService {

    private static final int MAX_MESSAGE_CHARS = 8000;
    private static final int MAX_CONTEXT_MESSAGES = 12;
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final ChatConversationRepository conversationRepository;
    private final ChatMessageRepository messageRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${app.deepseek.base-url:https://api.deepseek.com}")
    private String baseUrl;

    @Value("${app.deepseek.api-key:}")
    private String apiKey;

    @Value("${app.deepseek.model:deepseek-chat}")
    private String model;

    @Value("${app.deepseek.connect-timeout-ms:15000}")
    private long connectTimeoutMs;

    @Value("${app.deepseek.read-timeout-ms:120000}")
    private long readTimeoutMs;

    private volatile OkHttpClient client;
    private final Semaphore capacity = new Semaphore(8);
    private final Semaphore[] conversationGates = IntStream.range(0, 64)
            .mapToObj(index -> new Semaphore(1)).toArray(Semaphore[]::new);

    public AssistantChatResponse chat(UserAccount user, AssistantChatRequest request) {
        if (request == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "消息不能为空");
        String conversationId = normalize(request.getConversationId(), 80, "conversationId");
        Semaphore gate = conversationGates[Math.floorMod((user.getId() + ":" + conversationId).hashCode(), conversationGates.length)];
        if (!gate.tryAcquire()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "本轮对话正在回复，请稍候");
        }
        try {
            if (!capacity.tryAcquire()) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "提示词助手繁忙，请稍后重试");
            }
            try {
                return chatOnce(user, request);
            } finally {
                capacity.release();
            }
        } finally {
            gate.release();
        }
    }

    private AssistantChatResponse chatOnce(UserAccount user, AssistantChatRequest request) {
        String conversationId = normalize(request.getConversationId(), 80, "conversationId");
        String clientMessageId = normalize(request.getClientMessageId(), 120, "clientMessageId");
        String messageText = normalize(request.getMessage(), MAX_MESSAGE_CHARS, "message");
        if (apiKey == null || apiKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "提示词助手尚未配置");
        }

        ChatConversation conversation = conversationRepository.findFirstByUserIdAndClientIdAndDeletedAtIsNull(user.getId(), conversationId)
                .orElseGet(() -> {
                    if (conversationRepository.findDeletedClientIdsByUserId(user.getId()).contains(conversationId)) {
                        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "对话已删除，请新建对话");
                    }
                    return createConversation(user, conversationId, messageText);
                });
        conversation.setConversationType("prompt_assistant");
        conversation.setUpdatedAt(LocalDateTime.now());
        conversationRepository.save(conversation);

        List<ChatMessage> existing = messageRepository.findByConversationId(conversation.getId()).stream()
                .sorted(Comparator.comparing(ChatMessage::getCreatedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(ChatMessage::getId, Comparator.nullsFirst(Comparator.naturalOrder())))
                .toList();
        ChatMessage previousUserMessage = existing.stream()
                .filter(item -> clientMessageId.equals(item.getClientId()))
                .findFirst().orElse(null);
        String assistantId = request.getAssistantMessageId() == null || request.getAssistantMessageId().isBlank()
                ? "assistant-" + clientMessageId
                : normalize(request.getAssistantMessageId(), 120, "assistantMessageId");
        if (assistantId.length() > 120 || assistantId.equals(clientMessageId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "回复消息 ID 无效");
        }
        ChatMessage previousAssistantMessage = existing.stream()
                .filter(item -> assistantId.equals(item.getClientId()))
                .findFirst().orElse(null);
        // Compatibility with the old client, whose placeholder used a random ID.
        if (previousAssistantMessage == null && previousUserMessage != null
                && request.getAssistantMessageId() == null) {
            int index = existing.indexOf(previousUserMessage);
            if (index + 1 < existing.size()) {
                ChatMessage next = existing.get(index + 1);
                if ("assistant".equals(next.getRole()) && next.getGenerationRecordId() == null
                        && next.getVideoTaskCode() == null) {
                    previousAssistantMessage = next;
                }
            }
        }
        if (previousAssistantMessage != null && !"assistant".equals(previousAssistantMessage.getRole())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "回复消息 ID 已被占用");
        }
        if (previousAssistantMessage != null && "completed".equals(previousAssistantMessage.getIntent())
                && previousAssistantMessage.getContent() != null && !previousAssistantMessage.getContent().isBlank()) {
            return responseFromStored(conversationId, clientMessageId, previousAssistantMessage);
        }

        ChatMessage userMessage = previousUserMessage != null ? previousUserMessage : new ChatMessage();
        userMessage.setConversationId(conversation.getId());
        userMessage.setClientId(clientMessageId);
        userMessage.setRole("user");
        userMessage.setContent(messageText);
        userMessage.setIntent("assistant");
        messageRepository.save(userMessage);

        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt()));
        List<ChatMessage> history = existing.stream()
                .filter(item -> !clientMessageId.equals(item.getClientId()))
                .filter(item -> "user".equals(item.getRole()) || "assistant".equals(item.getRole()))
                .filter(item -> item.getContent() != null && !item.getContent().isBlank())
                .filter(item -> !"assistant".equals(item.getRole()) || "completed".equals(item.getIntent()))
                .toList();
        history.stream().skip(Math.max(0, history.size() - MAX_CONTEXT_MESSAGES))
                .forEach(item -> messages.add(Map.of(
                        "role", item.getRole(),
                        "content", contextContent(item))));
        messages.add(Map.of("role", "user", "content", messageText));

        ChatMessage assistantMessage = previousAssistantMessage != null ? previousAssistantMessage : new ChatMessage();
        assistantMessage.setConversationId(conversation.getId());
        if (assistantMessage.getClientId() == null) assistantMessage.setClientId(assistantId);
        assistantMessage.setRole("assistant");
        assistantMessage.setIntent("processing");
        messageRepository.save(assistantMessage);
        AssistantChatResponse parsed;
        try {
            parsed = parseResult(callDeepSeek(messages));
        } catch (ResponseStatusException e) {
            assistantMessage.setIntent("failed");
            assistantMessage.setContent(e.getReason());
            assistantMessage.setMetadata("{\"kind\":\"prompt_assistant\"}");
            messageRepository.save(assistantMessage);
            throw e;
        }
        assistantMessage.setContent(parsed.getReply());
        assistantMessage.setIntent("completed");
        assistantMessage.setMetadata(objectMapper.valueToTree(Map.of(
                "kind", "prompt_assistant",
                "prompt", nullToEmpty(parsed.getPrompt()),
                "negativePrompt", nullToEmpty(parsed.getNegativePrompt()),
                "questions", parsed.getQuestions(),
                "suggestions", parsed.getSuggestions())).toString());
        messageRepository.save(assistantMessage);

        parsed.setConversationId(conversationId);
        parsed.setClientMessageId(clientMessageId);
        parsed.setAssistantMessageId(assistantMessage.getClientId());
        parsed.setCreatedAt(toMillis(assistantMessage.getCreatedAt()));
        return parsed;
    }

    private ChatConversation createConversation(UserAccount user, String clientId, String message) {
        ChatConversation conversation = new ChatConversation();
        conversation.setUserId(user.getId());
        conversation.setUserEmail(user.getEmail());
        conversation.setClientId(clientId);
        conversation.setConversationType("prompt_assistant");
        conversation.setTitle(message.length() > 28 ? message.substring(0, 28) + "…" : message);
        return conversation;
    }

    private JsonNode callDeepSeek(List<Map<String, String>> messages) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("messages", messages);
            body.put("temperature", 0.6);
            body.put("max_tokens", 2048);
            body.put("response_format", Map.of("type", "json_object"));
            Request request = new Request.Builder()
                    .url(baseUrl.replaceAll("/$", "") + "/chat/completions")
                    .header("Authorization", "Bearer " + apiKey.trim())
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(objectMapper.writeValueAsString(body), JSON))
                    .build();
            try (Response response = client().newCall(request).execute()) {
                String raw = response.body() == null ? "" : response.body().string();
                if (!response.isSuccessful()) {
                    log.warn("DeepSeek assistant request failed: status={}", response.code());
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "提示词助手暂时不可用");
                }
                JsonNode root = objectMapper.readTree(raw);
                JsonNode content = root.path("choices").path(0).path("message").path("content");
                if (!content.isTextual() || content.asText().isBlank()) {
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "提示词助手返回为空");
                }
                return objectMapper.readTree(stripJsonFence(content.asText()));
            }
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.warn("DeepSeek assistant request error: {}", e.getClass().getSimpleName());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "提示词助手连接失败");
        }
    }

    private AssistantChatResponse parseResult(JsonNode result) {
        if (result == null || !result.isObject() || !result.path("reply").isTextual()
                || result.path("reply").asText().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "提示词助手未返回有效回复，请重试");
        }
        AssistantChatResponse response = new AssistantChatResponse();
        response.setReply(text(result, "reply", "我已经根据你的要求整理了提示词。"));
        response.setPrompt(text(result, "prompt", ""));
        response.setNegativePrompt(text(result, "negativePrompt", ""));
        response.setQuestions(strings(result.path("questions")));
        response.setSuggestions(strings(result.path("suggestions")));
        return response;
    }

    private String contextContent(ChatMessage message) {
        String content = message.getContent() == null ? "" : message.getContent();
        if (!"assistant".equals(message.getRole()) || message.getMetadata() == null) {
            return content;
        }
        try {
            JsonNode metadata = objectMapper.readTree(message.getMetadata());
            String prompt = text(metadata, "prompt", "");
            String negativePrompt = text(metadata, "negativePrompt", "");
            if (!prompt.isBlank()) content += "\nFinal prompt: " + prompt;
            if (!negativePrompt.isBlank()) content += "\nNegative prompt: " + negativePrompt;
        } catch (Exception ignored) {
        }
        return content;
    }

    private AssistantChatResponse responseFromStored(String conversationId, String clientMessageId, ChatMessage message) {
        AssistantChatResponse response = new AssistantChatResponse();
        response.setConversationId(conversationId);
        response.setClientMessageId(clientMessageId);
        response.setAssistantMessageId(message.getClientId());
        response.setReply(message.getContent());
        try {
            JsonNode metadata = objectMapper.readTree(message.getMetadata() == null ? "{}" : message.getMetadata());
            response.setPrompt(text(metadata, "prompt", ""));
            response.setNegativePrompt(text(metadata, "negativePrompt", ""));
            response.setQuestions(strings(metadata.path("questions")));
            response.setSuggestions(strings(metadata.path("suggestions")));
        } catch (Exception ignored) {
        }
        response.setCreatedAt(toMillis(message.getCreatedAt()));
        return response;
    }

    private String systemPrompt() {
        return "你是一个中文 AI 提示词助手。理解用户的创作意图，必要时最多追问两个关键问题；" +
                "然后给出适合图像生成模型的完整英文 prompt 和英文 negativePrompt。" +
                "保持用户明确指定的人物、主体、服装、构图和需要保留的元素；多轮对话中继承前文约束，" +
                "只修改用户本轮要求改变的部分。必须只返回 JSON，字段为 reply、prompt、negativePrompt、questions、suggestions。";
    }

    private OkHttpClient client() {
        if (client == null) {
            synchronized (this) {
                if (client == null) {
                    client = new OkHttpClient.Builder()
                            .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
                            .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
                            .callTimeout(readTimeoutMs + connectTimeoutMs, TimeUnit.MILLISECONDS)
                            .retryOnConnectionFailure(false)
                            .build();
                }
            }
        }
        return client;
    }

    private String normalize(String value, int maxLength, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + "不能为空");
        if (normalized.length() > maxLength) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + "长度超出限制");
        }
        return normalized;
    }

    private String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText() : fallback;
    }

    private List<String> strings(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node != null && node.isArray()) node.forEach(item -> { if (item.isTextual() && !item.asText().isBlank()) values.add(item.asText()); });
        return values;
    }

    private String stripJsonFence(String value) {
        String normalized = value.trim();
        if (normalized.startsWith("```")) {
            normalized = normalized.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
        }
        return normalized;
    }

    private String nullToEmpty(String value) { return value == null ? "" : value; }

    private Long toMillis(LocalDateTime time) {
        return time == null ? null : time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}
