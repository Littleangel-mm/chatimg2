package com.example.chatimg2.service;

import com.example.chatimg2.dto.AuthResponse;
import com.example.chatimg2.entity.Admin;
import com.example.chatimg2.entity.AuthSession;
import com.example.chatimg2.entity.UserAccount;
import com.example.chatimg2.repository.AdminRepository;
import com.example.chatimg2.repository.AuthSessionRepository;
import com.example.chatimg2.repository.UserAccountRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final AuthSessionRepository sessionRepository;
    private final UserAccountRepository userRepository;
    private final AdminRepository adminRepository;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int SESSION_DAYS = 14;

    @Transactional
    public AuthResponse createUserSession(UserAccount user) {
        user.setLastLoginAt(LocalDateTime.now());
        userRepository.save(user);
        return createSession(user, null, "USER");
    }

    @Transactional
    public AuthResponse createAdminSession(Admin admin) {
        return createSession(null, admin, "ADMIN");
    }

    public Optional<AuthPrincipal> current(HttpServletRequest request) {
        String token = bearerToken(request);
        if (token == null) return Optional.empty();
        return sessionRepository.findByTokenHash(sha256(token))
                .filter(session -> session.getExpiresAt().isAfter(LocalDateTime.now()))
                .flatMap(this::toPrincipal);
    }

    public AuthPrincipal requireUser(HttpServletRequest request) {
        AuthPrincipal principal = current(request)
                .orElseThrow(() -> new RuntimeException("请先登录"));
        if (!principal.isUser()) {
            throw new RuntimeException("请使用用户账号登录");
        }
        return principal;
    }

    public AuthPrincipal requireAdmin(HttpServletRequest request) {
        AuthPrincipal principal = current(request)
                .orElseThrow(() -> new RuntimeException("请先登录"));
        if (!principal.isAdmin()) {
            throw new RuntimeException("无管理员权限");
        }
        return principal;
    }

    @Transactional
    public void logout(HttpServletRequest request) {
        String token = bearerToken(request);
        if (token != null) {
            sessionRepository.deleteByTokenHash(sha256(token));
        }
    }

    @Transactional
    public void revokeUserSessions(Integer userId) {
        if (userId != null) {
            sessionRepository.deleteByUserId(userId);
        }
    }

    public String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }

    public boolean isQqEmail(String email) {
        return normalizeEmail(email).matches("^[A-Za-z0-9._%+-]+@qq\\.com$");
    }

    public String safeEmailFolder(String email) {
        String normalized = normalizeEmail(email);
        if (!normalized.matches("^[A-Za-z0-9._%+@-]+$")) {
            throw new RuntimeException("邮箱目录名不合法");
        }
        return normalized;
    }

    private AuthResponse createSession(UserAccount user, Admin admin, String role) {
        String rawToken = token();
        AuthSession session = new AuthSession();
        session.setTokenHash(sha256(rawToken));
        session.setUserId(user != null ? user.getId() : null);
        session.setAdminId(admin != null ? admin.getId() : null);
        session.setRole(role);
        session.setExpiresAt(LocalDateTime.now().plusDays(SESSION_DAYS));
        sessionRepository.save(session);

        if (user != null) {
            return new AuthResponse(
                    rawToken,
                    user.getId(),
                    user.getEmail(),
                    user.getUsername(),
                    role,
                    user.getTotalCredits(),
                    user.getUsedCredits(),
                    user.getRemainingCredits());
        }

        return new AuthResponse(rawToken, admin.getId(), null, admin.getUsername(), role, null, null, null);
    }

    private Optional<AuthPrincipal> toPrincipal(AuthSession session) {
        if ("ADMIN".equals(session.getRole()) && session.getAdminId() != null) {
            return adminRepository.findById(session.getAdminId())
                    .map(admin -> new AuthPrincipal("ADMIN", null, admin));
        }
        if (session.getUserId() != null) {
            return userRepository.findById(session.getUserId())
                    .filter(user -> "ACTIVE".equals(user.getStatus()))
                    .map(user -> new AuthPrincipal(user.getRole(), user, null));
        }
        return Optional.empty();
    }

    private String bearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return null;
        }
        String token = header.substring(7).trim();
        return token.isBlank() ? null : token;
    }

    private String token() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                String value = Integer.toHexString(0xff & b);
                if (value.length() == 1) hex.append('0');
                hex.append(value);
            }
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 unavailable", e);
        }
    }
}
