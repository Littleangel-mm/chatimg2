package com.example.chatimg2.controller;

import com.example.chatimg2.dto.ApiResponse;
import com.example.chatimg2.entity.GenerationRecord;
import com.example.chatimg2.entity.UserAccount;
import com.example.chatimg2.service.AuthPrincipal;
import com.example.chatimg2.service.AuthService;
import com.example.chatimg2.service.ImageGenerationService;
import com.example.chatimg2.service.KeyService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Arrays;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserControllerTest {

    @Test
    void multipartImageGenerationPassesImageBytesToUploadService() {
        KeyService keyService = mock(KeyService.class);
        ImageGenerationService generationService = mock(ImageGenerationService.class);
        AuthService authService = mock(AuthService.class);
        UserController controller = new UserController(keyService, generationService, authService);
        MockHttpServletRequest request = new MockHttpServletRequest();
        UserAccount user = new UserAccount();
        user.setId(7);
        user.setEmail("user@qq.com");
        byte[] jpeg = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0x01};
        MockMultipartFile source = new MockMultipartFile(
                "sourceImage", "source.jpg", "image/jpeg", jpeg);
        GenerationRecord record = new GenerationRecord();
        record.setId(12);

        when(authService.current(request)).thenReturn(Optional.of(new AuthPrincipal("USER", user, null)));
        when(generationService.submitGenerationForUserUpload(
                eq(user), eq("edit this image"), eq("img2img"),
                argThat(bytes -> Arrays.equals(bytes, jpeg)), eq("message-1")))
                .thenReturn(record);

        ApiResponse<GenerationRecord> response = controller.generateMultipart(
                null, "edit this image", "img2img", "message-1", source, request);

        assertThat(response.getCode()).isEqualTo(200);
        assertThat(response.getData()).isSameAs(record);
        verify(generationService).submitGenerationForUserUpload(
                eq(user), eq("edit this image"), eq("img2img"),
                argThat(bytes -> Arrays.equals(bytes, jpeg)), eq("message-1"));
    }
}
