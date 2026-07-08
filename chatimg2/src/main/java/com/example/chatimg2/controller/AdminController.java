package com.example.chatimg2.controller;

import com.example.chatimg2.dto.AdminUserDto;
import com.example.chatimg2.dto.ApiResponse;
import com.example.chatimg2.dto.KeyRequest;
import com.example.chatimg2.dto.LoginRequest;
import com.example.chatimg2.dto.PageResult;
import com.example.chatimg2.dto.ResetPasswordResponse;
import com.example.chatimg2.entity.ActivationKey;
import com.example.chatimg2.entity.Admin;
import com.example.chatimg2.entity.GenerationRecord;
import com.example.chatimg2.entity.UserAccount;
import com.example.chatimg2.repository.UserAccountRepository;
import com.example.chatimg2.service.AuthService;
import com.example.chatimg2.service.AdminService;
import com.example.chatimg2.service.ImageGenerationService;
import com.example.chatimg2.service.KeyService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;
    private final KeyService keyService;
    private final AuthService authService;
    private final UserAccountRepository userRepository;
    private final ImageGenerationService imageGenerationService;

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final SecureRandom RANDOM = new SecureRandom();

    @PostMapping("/login")
    public ApiResponse<Admin> login(@RequestBody LoginRequest request) {
        Optional<Admin> admin = adminService.login(request.getUsername(), request.getPassword());
        if (admin.isPresent()) {
            Admin a = admin.get();
            a.setPassword(null);
            return ApiResponse.success("登录成功", a);
        }
        return ApiResponse.error(401, "用户名或密码错误");
    }

    @GetMapping("/keys")
    public ApiResponse<PageResult<ActivationKey>> listKeys(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            HttpServletRequest request) {
        authService.requireAdmin(request);
        return ApiResponse.success(keyService.getKeysPage(page, size));
    }

    @GetMapping("/users")
    public ApiResponse<List<AdminUserDto>> listUsers(HttpServletRequest request) {
        authService.requireAdmin(request);
        List<AdminUserDto> users = userRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(this::toUserDto)
                .collect(Collectors.toList());
        return ApiResponse.success(users);
    }

    @PostMapping("/users/{id}/reset-password")
    public ApiResponse<ResetPasswordResponse> resetPassword(@PathVariable Integer id, HttpServletRequest request) {
        authService.requireAdmin(request);
        UserAccount user = userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("用户不存在"));
        String tempPassword = generateTempPassword();
        user.setPasswordHash(authService.sha256(user.getEmail() + ":" + tempPassword));
        userRepository.save(user);
        return ApiResponse.success("密码已重置", new ResetPasswordResponse(
                user.getId(),
                user.getEmail(),
                tempPassword,
                "******"));
    }

    @GetMapping("/generations")
    public ApiResponse<List<GenerationRecord>> listGenerations(HttpServletRequest request) {
        authService.requireAdmin(request);
        return ApiResponse.success(imageGenerationService.getAdminHistory());
    }

    @PostMapping("/keys")
    public ApiResponse<ActivationKey> createKey(@RequestBody(required = false) KeyRequest request,
                                                HttpServletRequest servletRequest) {
        authService.requireAdmin(servletRequest);
        try {
            Integer totalCredits = request != null ? request.getTotalCredits() : null;
            ActivationKey key = keyService.createKey(totalCredits);
            return ApiResponse.success("密钥创建成功", key);
        } catch (RuntimeException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    @PutMapping("/keys/{id}")
    public ApiResponse<ActivationKey> updateKey(@PathVariable Integer id, @RequestBody KeyRequest request,
                                                HttpServletRequest servletRequest) {
        authService.requireAdmin(servletRequest);
        try {
            if (request == null || request.getTotalCredits() == null) {
                return ApiResponse.error(400, "请填写积分");
            }
            ActivationKey key = keyService.updateKeyCredits(id, request.getTotalCredits());
            return ApiResponse.success("密钥更新成功", key);
        } catch (RuntimeException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    @PostMapping("/keys/{id}/reset-used")
    public ApiResponse<ActivationKey> resetUsedCredits(@PathVariable Integer id, HttpServletRequest servletRequest) {
        authService.requireAdmin(servletRequest);
        try {
            ActivationKey key = keyService.resetUsedCredits(id);
            return ApiResponse.success("已用积分已重置", key);
        } catch (RuntimeException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    @DeleteMapping("/keys/{id}")
    public ApiResponse<Void> deleteKey(@PathVariable Integer id, HttpServletRequest servletRequest) {
        authService.requireAdmin(servletRequest);
        try {
            keyService.deleteKey(id);
            return ApiResponse.success("密钥删除成功", null);
        } catch (RuntimeException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    private AdminUserDto toUserDto(UserAccount user) {
        List<String> boundKeys = new ArrayList<>();
        if (user.getEmail() != null && !user.getEmail().isBlank()) {
            keyService.getBoundKeysByUser(user).forEach(key -> boundKeys.add(key.getKeyCode()));
        }
        return new AdminUserDto(
                user.getId(),
                user.getEmail(),
                user.getUsername(),
                "******",
                user.getRole(),
                user.getStatus(),
                user.getTotalCredits(),
                user.getUsedCredits(),
                user.getRemainingCredits(),
                formatDate(user.getCreatedAt()),
                formatDate(user.getLastLoginAt()),
                boundKeys,
                boundKeys.size()
        );
    }

    private String formatDate(java.time.LocalDateTime time) {
        return time == null ? null : time.format(FORMATTER);
    }

    private String generateTempPassword() {
        byte[] bytes = new byte[6];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).substring(0, 8);
    }
}
