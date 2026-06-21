package com.example.chatimg2.controller;

import com.example.chatimg2.dto.ApiResponse;
import com.example.chatimg2.dto.GenerateRequest;
import com.example.chatimg2.entity.ActivationKey;
import com.example.chatimg2.entity.GenerationRecord;
import com.example.chatimg2.service.ImageGenerationService;
import com.example.chatimg2.service.KeyService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {

    private final KeyService keyService;
    private final ImageGenerationService imageGenerationService;

    @PostMapping("/activate")
    public ApiResponse<Map<String, Object>> activate(@RequestBody Map<String, String> request) {
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

        Map<String, Object> data = new HashMap<>();
        data.put("keyId", key.getId());
        data.put("keyCode", key.getKeyCode());
        data.put("totalCredits", key.getTotalCredits());
        data.put("usedCredits", key.getUsedCredits());
        data.put("remainingCredits", key.getRemainingCredits());

        return ApiResponse.success("激活成功", data);
    }

    @PostMapping("/generate")
    public ApiResponse<GenerationRecord> generate(@RequestBody GenerateRequest request) {
        if (request.getKeyCode() == null || request.getKeyCode().isBlank()) {
            return ApiResponse.error(400, "请提供密钥");
        }
        if (request.getPrompt() == null || request.getPrompt().isBlank()) {
            return ApiResponse.error(400, "请输入提示词");
        }

        try {
            GenerationRecord record = imageGenerationService.submitGeneration(
                    request.getKeyCode(),
                    request.getPrompt(),
                    request.getType(),
                    request.getSourceImage(),
                    request.getModel());
            return ApiResponse.success("任务已提交，正在生成中", record);
        } catch (RuntimeException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    @GetMapping("/generate/{recordId}")
    public ApiResponse<GenerationRecord> getGenerateStatus(
            @PathVariable Integer recordId,
            @RequestParam String keyCode) {
        try {
            GenerationRecord record = imageGenerationService.getRecordStatus(keyCode, recordId);
            return ApiResponse.success(record);
        } catch (RuntimeException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    @GetMapping("/history")
    public ApiResponse<List<GenerationRecord>> getHistory(@RequestParam String keyCode) {
        try {
            List<GenerationRecord> history = imageGenerationService.getHistory(keyCode);
            return ApiResponse.success(history);
        } catch (RuntimeException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }
}
