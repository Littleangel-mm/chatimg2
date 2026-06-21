package com.example.chatimg2.controller;

import com.example.chatimg2.dto.ApiResponse;
import com.example.chatimg2.dto.KeyRequest;
import com.example.chatimg2.dto.LoginRequest;
import com.example.chatimg2.dto.PageResult;
import com.example.chatimg2.entity.ActivationKey;
import com.example.chatimg2.entity.Admin;
import com.example.chatimg2.service.AdminService;
import com.example.chatimg2.service.KeyService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;
    private final KeyService keyService;

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
            @RequestParam(defaultValue = "10") int size) {
        return ApiResponse.success(keyService.getKeysPage(page, size));
    }

    @PostMapping("/keys")
    public ApiResponse<ActivationKey> createKey(@RequestBody(required = false) KeyRequest request) {
        try {
            Integer totalCredits = request != null ? request.getTotalCredits() : null;
            ActivationKey key = keyService.createKey(totalCredits);
            return ApiResponse.success("密钥创建成功", key);
        } catch (RuntimeException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    @PutMapping("/keys/{id}")
    public ApiResponse<ActivationKey> updateKey(@PathVariable Integer id, @RequestBody KeyRequest request) {
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
    public ApiResponse<ActivationKey> resetUsedCredits(@PathVariable Integer id) {
        try {
            ActivationKey key = keyService.resetUsedCredits(id);
            return ApiResponse.success("已用积分已重置", key);
        } catch (RuntimeException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    @DeleteMapping("/keys/{id}")
    public ApiResponse<Void> deleteKey(@PathVariable Integer id) {
        try {
            keyService.deleteKey(id);
            return ApiResponse.success("密钥删除成功", null);
        } catch (RuntimeException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }
}
