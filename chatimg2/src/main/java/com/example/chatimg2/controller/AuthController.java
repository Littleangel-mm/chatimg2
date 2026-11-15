package com.example.chatimg2.controller;

import com.example.chatimg2.dto.ApiResponse;
import com.example.chatimg2.dto.AuthRequest;
import com.example.chatimg2.dto.AuthResponse;
import com.example.chatimg2.entity.Admin;
import com.example.chatimg2.entity.UserAccount;
import com.example.chatimg2.repository.UserAccountRepository;
import com.example.chatimg2.service.AdminService;
import com.example.chatimg2.service.AuthPrincipal;
import com.example.chatimg2.service.AuthService;
import com.example.chatimg2.service.EmailCodeService;
import com.example.chatimg2.service.KeyService;
import com.example.chatimg2.service.PasswordService;
import com.example.chatimg2.service.RegistrationService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final EmailCodeService emailCodeService;
    private final UserAccountRepository userRepository;
    private final AdminService adminService;
    private final KeyService keyService;
    private final PasswordService passwordService;
    private final RegistrationService registrationService;

    @PostMapping("/email-code")
    public ApiResponse<Void> sendEmailCode(@RequestBody AuthRequest request, HttpServletRequest servletRequest) {
        try {
            String email = request.getEmail() != null ? request.getEmail() : request.getAccount();
            emailCodeService.sendCode(email, request.getScene(), clientIp(servletRequest));
            return ApiResponse.success("验证码已发送", null);
        } catch (RuntimeException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    @PostMapping("/register")
    public ApiResponse<AuthResponse> register(@RequestBody AuthRequest request) {
        try {
            String email = authService.normalizeEmail(request.getEmail() != null ? request.getEmail() : request.getAccount());
            if (!authService.isQqEmail(email)) {
                return ApiResponse.error(400, "请输入有效的 QQ 邮箱");
            }
            if (userRepository.existsByEmail(email)) {
                return ApiResponse.error(400, "该邮箱已注册，请直接登录");
            }
            if (request.getPassword() == null || request.getPassword().length() < 6) {
                return ApiResponse.error(400, "密码至少 6 位");
            }
            if (!passwordService.isBcryptLengthSupported(request.getPassword())) {
                return ApiResponse.error(400, "密码不能超过 72 个字节");
            }

            AuthResponse response = registrationService.register(
                    email, request.getPassword(), request.getCode());
            return ApiResponse.success("注册成功", response);
        } catch (DataIntegrityViolationException e) {
            return ApiResponse.error(400, "该邮箱已注册，请直接登录");
        } catch (RuntimeException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    @PostMapping("/login")
    public ApiResponse<AuthResponse> login(@RequestBody AuthRequest request) {
        try {
            String account = request.getAccount() != null ? request.getAccount().trim() : "";
            if (authService.isQqEmail(account)) {
                String email = authService.normalizeEmail(account);
                UserAccount user = userRepository.findByEmail(email)
                        .orElseThrow(() -> new RuntimeException("该邮箱未注册，请先注册"));
                if (request.getPassword() == null || request.getPassword().isBlank()) {
                    return ApiResponse.error(400, "请输入密码");
                }
                if (!passwordService.matchesUser(email, request.getPassword(), user.getPasswordHash())) {
                    return ApiResponse.error(401, "邮箱或密码错误");
                }
                if (passwordService.needsUpgrade(user.getPasswordHash())
                        && passwordService.isBcryptLengthSupported(request.getPassword())) {
                    user.setPasswordHash(passwordService.encode(request.getPassword()));
                    user = userRepository.save(user);
                }
                user = keyService.syncUserCredits(user);
                return ApiResponse.success("登录成功", authService.createUserSession(user));
            }

            Optional<Admin> admin = adminService.login(account, request.getPassword());
            if (admin.isEmpty()) {
                return ApiResponse.error(401, "账号或密码错误");
            }
            return ApiResponse.success("登录成功", authService.createAdminSession(admin.get()));
        } catch (RuntimeException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    @GetMapping("/me")
    public ApiResponse<AuthResponse> me(HttpServletRequest request) {
        Optional<AuthPrincipal> principal = authService.current(request);
        if (principal.isEmpty()) {
            return ApiResponse.error(401, "请先登录");
        }
        AuthPrincipal p = principal.get();
        if (p.isUser()) {
            UserAccount user = keyService.syncUserCredits(p.user());
            return ApiResponse.success(new AuthResponse(
                    null,
                    user.getId(),
                    user.getEmail(),
                    user.getUsername(),
                    user.getRole(),
                    user.getTotalCredits(),
                    user.getUsedCredits(),
                    user.getRemainingCredits()));
        }
        return ApiResponse.success(new AuthResponse(
                null,
                p.admin().getId(),
                null,
                p.admin().getUsername(),
                "ADMIN",
                null,
                null,
                null));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest request) {
        authService.logout(request);
        return ApiResponse.success("已退出", null);
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
