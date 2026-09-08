package com.example.chatimg2.controller;

import com.example.chatimg2.entity.UserAccount;
import com.example.chatimg2.repository.GenerationRecordRepository;
import com.example.chatimg2.repository.UserAccountRepository;
import com.example.chatimg2.service.AuthService;
import com.example.chatimg2.service.AsyncGenerationExecutor;
import com.example.chatimg2.service.KeyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MultiImageGenerationTest {
    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired KeyService keys;
    @Autowired UserAccountRepository users;
    @Autowired GenerationRecordRepository records;
    @Autowired ObjectMapper json;
    @Autowired jakarta.persistence.EntityManager entityManager;
    @MockitoBean AsyncGenerationExecutor executor;

    private MockMultipartFile file(String name) {
        return new MockMultipartFile(name, "sample.png", "image/png",
                new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 1});
    }

    private UserAccount user() {
        var user = new UserAccount();
        user.setEmail(UUID.randomUUID() + "@qq.com");
        return users.saveAndFlush(user);
    }

    @Test
    void rejectsAnonymousAndInvalidTokenAndInvalidKey() throws Exception {
        long before = records.count();
        for (String token : List.of("", "Bearer invalid")) {
            mvc.perform(multipart("/api/user/generate").file(file("sourceImages")).file(file("sourceImages"))
                    .param("prompt", "combine").header("Authorization", token))
                    .andExpect(jsonPath("$.code").value(401));
        }
        mvc.perform(multipart("/api/user/generate").file(file("sourceImages"))
                .param("prompt", "combine").param("keyCode", "INVALID"))
                .andExpect(jsonPath("$.code").value(401));
        assertThat(records.count()).isEqualTo(before);
    }

    @Test
    void persistsBothReferencesAndChargesOnceAndPreventsOtherUserReading() throws Exception {
        var owner = user();
        String token = auth.createUserSession(owner).getToken();
        String message = UUID.randomUUID().toString();
        var response = mvc.perform(multipart("/api/user/generate")
                .file(file("sourceImages")).file(file("sourceImages"))
                .param("prompt", "combine").param("clientMessageId", message)
                .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.sourceImagePaths.length()").value(2))
                .andReturn();
        int id = json.readTree(response.getResponse().getContentAsString()).path("data").path("id").asInt();
        records.flush();
        entityManager.clear();
        var saved = records.findById(id).orElseThrow();
        assertThat(saved.getSourceImagePaths()).hasSize(2);
        assertThat(saved.getSourceImagePath()).isEqualTo(saved.getSourceImagePaths().get(0));
        var chat = Map.of("conversations", List.of(Map.of(
                "clientId", "multi-chat", "type", "img2img", "title", "combine",
                "createdAt", System.currentTimeMillis(), "updatedAt", System.currentTimeMillis(),
                "messages", List.of(
                    Map.of("clientId", "input", "role", "user", "content", "combine", "createdAt", System.currentTimeMillis()),
                    Map.of("clientId", message, "role", "assistant", "recordId", id, "createdAt", System.currentTimeMillis() + 1)))));
        mvc.perform(post("/api/chat/sync").header("Authorization", "Bearer " + token)
                .contentType("application/json").content(json.writeValueAsString(chat)))
                .andExpect(jsonPath("$.code").value(200));
        mvc.perform(get("/api/chat/sync").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.conversations[0].messages[1].recordSourceImagePaths.length()").value(2));
        mvc.perform(multipart("/api/user/generate").file(file("sourceImages")).file(file("sourceImages"))
                .param("prompt", "combine").param("clientMessageId", message)
                .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.id").value(id));
        assertThat(users.findById(owner.getId()).orElseThrow().getUsedCredits()).isEqualTo(20);
        String other = auth.createUserSession(user()).getToken();
        mvc.perform(get("/api/user/generate/" + id).header("Authorization", "Bearer " + other))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void validKeySupportsMultipleFiles() throws Exception {
        var key = keys.createKey(100);
        mvc.perform(multipart("/api/user/generate").file(file("sourceImages")).file(file("sourceImages"))
                .param("prompt", "combine").param("keyCode", key.getKeyCode()))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.sourceImagePaths.length()").value(2));
    }

    @Test
    void acceptsFourImagesAndRejectsOversizedImage() throws Exception {
        String token = auth.createUserSession(user()).getToken();
        mvc.perform(multipart("/api/user/generate")
                .file(file("sourceImages")).file(file("sourceImages"))
                .file(file("sourceImages")).file(file("sourceImages"))
                .param("prompt", "combine").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.sourceImagePaths.length()").value(4));
        var oversized = new MockMultipartFile("sourceImages", "large.png", "image/png", new byte[]{1}) {
            @Override public long getSize() { return 20L * 1024 * 1024 + 1; }
        };
        mvc.perform(multipart("/api/user/generate").file(oversized)
                .param("prompt", "combine").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void rejectsExcessOrEmptyOrInvalidSecondImageWithoutCharging() throws Exception {
        var owner = user();
        String token = auth.createUserSession(owner).getToken();
        var excessive = multipart("/api/user/generate");
        excessive.param("prompt", "combine").header("Authorization", "Bearer " + token);
        for (int i = 0; i < 5; i++) excessive.file(file("sourceImages"));
        mvc.perform(excessive).andExpect(jsonPath("$.code").value(400));
        mvc.perform(multipart("/api/user/generate").param("prompt", "combine")
                .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(400));
        mvc.perform(multipart("/api/user/generate").file(file("sourceImages"))
                .file(new MockMultipartFile("sourceImages", "bad.png", "image/png", new byte[]{1, 2, 3}))
                .param("prompt", "combine").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(400));
        assertThat(users.findById(owner.getId()).orElseThrow().getUsedCredits()).isZero();
    }

    @Test
    void supportsJsonMultipleAndLegacySingle() throws Exception {
        String token = auth.createUserSession(user()).getToken();
        String image = "iVBORwE=";
        for (var payload : List.of(
                Map.of("prompt", "combine", "type", "img2img", "sourceImages", List.of(image, image)),
                Map.of("prompt", "edit", "type", "img2img", "sourceImage", image))) {
            mvc.perform(post("/api/user/generate").contentType("application/json")
                    .header("Authorization", "Bearer " + token).content(json.writeValueAsString(payload)))
                    .andExpect(jsonPath("$.code").value(200));
        }
    }
}
