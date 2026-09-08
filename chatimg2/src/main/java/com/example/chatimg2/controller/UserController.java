package com.example.chatimg2.controller;

import com.example.chatimg2.dto.ApiResponse;
import com.example.chatimg2.dto.GenerateRequest;
import com.example.chatimg2.entity.ActivationKey;
import com.example.chatimg2.entity.GenerationRecord;
import com.example.chatimg2.exception.AppException;
import com.example.chatimg2.exception.BusinessException;
import com.example.chatimg2.service.AuthPrincipal;
import com.example.chatimg2.service.AuthService;
import com.example.chatimg2.service.ImageGenerationService;
import com.example.chatimg2.service.KeyService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ArrayList;

@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
@Slf4j
public class UserController {

    private final KeyService keyService;
    private final ImageGenerationService imageGenerationService;
    private final AuthService authService;

    @PostMapping("/activate")
    public ApiResponse<Map<String, Object>> activate(@RequestBody Map<String, String> request, HttpServletRequest servletRequest) {
        String keyCode = request.get("keyCode");
        if (keyCode == null || keyCode.isBlank()) {
            return ApiResponse.error(400, "请输入密钥");
        }
        keyCode = keyCode.trim().toUpperCase();

        Optional<ActivationKey> keyOpt = keyService.activate(keyCode);
        if (keyOpt.isEmpty()) {
            return ApiResponse.error(401, "密钥无效或已禁用");
        }

        ActivationKey key = keyOpt.get();
        Optional<AuthPrincipal> principal = authService.current(servletRequest);
        if (principal.isPresent() && principal.get().isUser()) {
            key = keyService.bindToUser(key, principal.get().user());
        }

        Map<String, Object> data = new HashMap<>();
        data.put("keyId", key.getId());
        data.put("keyCode", key.getKeyCode());
        data.put("totalCredits", key.getTotalCredits());
        data.put("usedCredits", key.getUsedCredits());
        data.put("remainingCredits", key.getRemainingCredits());
        data.put("userId", key.getUserId());
        data.put("userEmail", key.getUserEmail());

        return ApiResponse.success("激活成功", data);
    }

    @PostMapping(value = "/generate", consumes = "application/json")
    public ApiResponse<GenerationRecord> generate(@RequestBody GenerateRequest request, HttpServletRequest servletRequest) {
        log.info("Image submission received: requestId={}, clientMessageId={}, type={}",
                servletRequest.getHeader("X-Request-ID"), request.getClientMessageId(), request.getType());
        if (request.getPrompt() == null || request.getPrompt().isBlank()) {
            return ApiResponse.error(400, "请输入提示词");
        }

        try {
            Optional<AuthPrincipal> principal = authService.current(servletRequest);
            if (principal.isPresent() && !principal.get().isUser()) {
                return ApiResponse.error(403, "请使用用户账号生成图片");
            }
            GenerationRecord record;
            if (principal.isPresent() && principal.get().isUser()) {
                record = imageGenerationService.submitGenerationForUser(
                        principal.get().user(),
                        request.getPrompt(),
                        request.getType(),
                        request.getSourceImages() != null ? request.getSourceImages() : (request.getSourceImage() == null ? List.of() : List.of(request.getSourceImage())),
                        request.getClientMessageId());
            } else {
                if (request.getKeyCode() == null || request.getKeyCode().isBlank()) {
                    return ApiResponse.error(401, "请先登录或激活密钥");
                }
                record = imageGenerationService.submitGeneration(
                        request.getKeyCode(),
                        request.getPrompt(),
                        request.getType(),
                        request.getSourceImages() != null ? request.getSourceImages() : (request.getSourceImage() == null ? List.of() : List.of(request.getSourceImage())),
                        request.getClientMessageId());
            }
            return ApiResponse.success("任务已提交，正在生成中", record);
        } catch (RuntimeException e) {
            return requestError(e);
        }
    }

    public ApiResponse<GenerationRecord> generateMultipart(
            String keyCode, String prompt, String type, String clientMessageId,
            MultipartFile sourceImage, HttpServletRequest servletRequest) {
        return generateMultipart(keyCode, prompt, type, clientMessageId, sourceImage, null, servletRequest);
    }

    @PostMapping(value = "/generate", consumes = "multipart/form-data")
    public ApiResponse<GenerationRecord> generateMultipart(
            @RequestParam(required = false) String keyCode,
            @RequestParam String prompt,
            @RequestParam(defaultValue = "img2img") String type,
            @RequestParam(required = false) String clientMessageId,
            @RequestPart(value = "sourceImage", required = false) MultipartFile sourceImage,
            @RequestPart(value = "sourceImages", required = false) List<MultipartFile> sourceImages,
            HttpServletRequest servletRequest) {
        log.info("Image multipart submission received: requestId={}, clientMessageId={}, type={}, fileName={}, contentType={}, size={}",
                servletRequest.getHeader("X-Request-ID"), clientMessageId, type,
                sourceImage == null ? null : sourceImage.getOriginalFilename(),
                sourceImage == null ? null : sourceImage.getContentType(),
                sourceImage == null ? 0 : sourceImage.getSize());
        if (prompt.isBlank()) {
            return ApiResponse.error(400, "请输入提示词");
        }
        List<MultipartFile> files = new ArrayList<>();
        if (sourceImages != null) files.addAll(sourceImages);
        if (sourceImage != null && !sourceImage.isEmpty()) files.add(0, sourceImage);
        if (files.isEmpty() || files.size() > 4 || files.stream().anyMatch(file -> file == null || file.isEmpty() || file.getSize() > 20L * 1024 * 1024)) {
            return ApiResponse.error(400, "参考图片不能为空，最多支持 4 张且单张不超过 20MB");
        }

        try {
            Optional<AuthPrincipal> principal = authService.current(servletRequest);
            if (principal.isPresent() && !principal.get().isUser()) {
                return ApiResponse.error(403, "请使用用户账号生成图片");
            }
            if (principal.isEmpty() && keyService.activate(keyCode).isEmpty()) {
                return ApiResponse.error(401, "请先登录或激活有效密钥");
            }
            List<byte[]> imageBytes = new ArrayList<>();
            for (MultipartFile file : files) imageBytes.add(file.getBytes());
            GenerationRecord record;
            if (principal.isPresent() && principal.get().isUser()) {
                record = imageBytes.size() == 1
                        ? imageGenerationService.submitGenerationForUserUpload(
                        principal.get().user(), prompt, type, imageBytes.get(0), clientMessageId)
                        : imageGenerationService.submitGenerationForUserUploadMultiple(
                        principal.get().user(), prompt, type, imageBytes, clientMessageId);
            } else {
                if (keyCode == null || keyCode.isBlank()) {
                    return ApiResponse.error(401, "请先登录或激活密钥");
                }
                record = imageBytes.size() == 1
                        ? imageGenerationService.submitGenerationUpload(
                        keyCode, prompt, type, imageBytes.get(0), clientMessageId)
                        : imageGenerationService.submitGenerationUploadMultiple(
                        keyCode, prompt, type, imageBytes, clientMessageId);
            }
            return ApiResponse.success("任务已提交，正在生成中", record);
        } catch (java.io.IOException e) {
            log.warn("Read source image upload failed", e);
            return ApiResponse.error(400, "参考图片上传内容无效");
        } catch (RuntimeException e) {
            return requestError(e);
        }
    }

    @GetMapping("/generate/{recordId}")
    public ApiResponse<GenerationRecord> getGenerateStatus(
            @PathVariable Integer recordId,
            @RequestParam(required = false) String keyCode,
            HttpServletRequest servletRequest) {
        try {
            Optional<AuthPrincipal> principal = authService.current(servletRequest);
            GenerationRecord record;
            if (principal.isPresent() && principal.get().isUser()) {
                record = imageGenerationService.getRecordStatusForUser(principal.get().user(), recordId);
            } else {
                if (keyCode == null || keyCode.isBlank()) {
                    return ApiResponse.error(401, "请先登录或激活密钥");
                }
                record = imageGenerationService.getRecordStatus(keyCode, recordId);
            }
            return ApiResponse.success(record);
        } catch (RuntimeException e) {
            return requestError(e);
        }
    }

    @PostMapping("/generate/{recordId}/backup")
    public ApiResponse<Void> retryImageBackup(
            @PathVariable Integer recordId,
            @RequestParam(required = false) String keyCode,
            HttpServletRequest servletRequest) {
        try {
            Optional<AuthPrincipal> principal = authService.current(servletRequest);
            if (principal.isPresent() && principal.get().isUser()) {
                imageGenerationService.retryRemoteImageBackupForUser(principal.get().user(), recordId);
            } else {
                if (keyCode == null || keyCode.isBlank()) {
                    return ApiResponse.error(401, "请先登录或激活密钥");
                }
                imageGenerationService.retryRemoteImageBackup(keyCode, recordId);
            }
            return ApiResponse.success(null);
        } catch (RuntimeException e) {
            return requestError(e);
        }
    }

    @PostMapping(value = "/generate/{recordId}/backup-upload", consumes = "multipart/form-data")
    public ApiResponse<Void> uploadImageBackup(
            @PathVariable Integer recordId,
            @RequestPart("image") MultipartFile image,
            @RequestParam(required = false) String keyCode,
            HttpServletRequest servletRequest) {
        try {
            if (image.isEmpty() || image.getSize() > 20L * 1024 * 1024) {
                return ApiResponse.error(400, "图片为空或超过 20MB");
            }
            Optional<AuthPrincipal> principal = authService.current(servletRequest);
            if (principal.isPresent() && principal.get().isUser()) {
                imageGenerationService.getRecordStatusForUser(principal.get().user(), recordId);
                imageGenerationService.saveBrowserImageBackupForUser(
                        principal.get().user(), recordId, image.getBytes());
            } else {
                if (keyCode == null || keyCode.isBlank()) {
                    return ApiResponse.error(401, "请先登录或激活密钥");
                }
                imageGenerationService.getRecordStatus(keyCode, recordId);
                imageGenerationService.saveBrowserImageBackup(keyCode, recordId, image.getBytes());
            }
            return ApiResponse.success(null);
        } catch (RuntimeException e) {
            return requestError(e);
        } catch (java.io.IOException e) {
            log.warn("Read browser image backup failed: recordId={}", recordId, e);
            return ApiResponse.error(400, "图片上传内容无效");
        }
    }

    @GetMapping("/history")
    public ApiResponse<List<GenerationRecord>> getHistory(
            @RequestParam(required = false) String keyCode,
            HttpServletRequest servletRequest) {
        try {
            Optional<AuthPrincipal> principal = authService.current(servletRequest);
            List<GenerationRecord> history;
            if (principal.isPresent() && principal.get().isUser()) {
                history = imageGenerationService.getHistoryForUser(principal.get().user());
            } else {
                if (keyCode == null || keyCode.isBlank()) {
                    return ApiResponse.error(401, "请先登录或激活密钥");
                }
                history = imageGenerationService.getHistory(keyCode);
            }
            return ApiResponse.success(history);
        } catch (RuntimeException e) {
            return requestError(e);
        }
    }

    private <T> ApiResponse<T> requestError(RuntimeException error) {
        if (error instanceof BusinessException businessError) {
            return ApiResponse.error(400, businessError.getUserMessage());
        }
        if (error instanceof AppException appError) {
            log.error("User image request failed: code={}", appError.getErrorCode(), appError);
            return ApiResponse.error(500, appError.getUserMessage());
        }
        log.error("Unexpected user image request failure", error);
        return ApiResponse.error(500, "请求处理失败，请稍后重试");
    }
}
