package com.example.chatseedance2.security;

import com.example.chatseedance2.entity.AuthSession;
import com.example.chatseedance2.entity.Admin;
import com.example.chatseedance2.entity.UserAccount;
import com.example.chatseedance2.exception.ForbiddenException;
import com.example.chatseedance2.exception.UnauthorizedException;
import com.example.chatseedance2.repository.SeedanceAdminRepository;
import com.example.chatseedance2.repository.SeedanceAuthSessionRepository;
import com.example.chatseedance2.repository.SeedanceUserAccountRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Optional;

@Service("videoAuthService")
@RequiredArgsConstructor
public class AuthService {

    public static final String PRINCIPAL_ATTRIBUTE = AuthService.class.getName() + ".principal";

    private final SeedanceAuthSessionRepository sessionRepository;
    private final SeedanceUserAccountRepository userRepository;
    private final SeedanceAdminRepository adminRepository;

    public Optional<AuthPrincipal> current(HttpServletRequest request) {
        Object cached = request.getAttribute(PRINCIPAL_ATTRIBUTE);
        if (cached instanceof AuthPrincipal principal) {
            return Optional.of(principal);
        }
        String token = bearerToken(request);
        if (token == null) {
            return Optional.empty();
        }
        Optional<AuthPrincipal> principal = sessionRepository.findByTokenHash(sha256(token))
                .filter(session -> session.getExpiresAt().isAfter(LocalDateTime.now()))
                .flatMap(this::toPrincipal);
        principal.ifPresent(value -> request.setAttribute(PRINCIPAL_ATTRIBUTE, value));
        return principal;
    }

    public AuthPrincipal requireCurrent(HttpServletRequest request) {
        return current(request).orElseThrow(() -> new UnauthorizedException("请先登录"));
    }

    public UserAccount requireUser(HttpServletRequest request) {
        AuthPrincipal principal = requireCurrent(request);
        if (!principal.isUser()) {
            throw new ForbiddenException("仅普通用户可以访问此功能");
        }
        return principal.user();
    }

    public Admin requireAdmin(HttpServletRequest request) {
        AuthPrincipal principal = requireCurrent(request);
        if (!principal.isAdmin()) {
            throw new ForbiddenException("仅管理员可以访问此功能");
        }
        return principal.admin();
    }

    private Optional<AuthPrincipal> toPrincipal(AuthSession session) {
        if ("ADMIN".equals(session.getRole()) && session.getAdminId() != null) {
            return adminRepository.findById(session.getAdminId())
                    .map(admin -> new AuthPrincipal(AppRole.ADMIN, null, admin));
        }
        if (session.getUserId() == null) {
            return Optional.empty();
        }
        return userRepository.findById(session.getUserId())
                .filter(user -> "ACTIVE".equals(user.getStatus()))
                .map(user -> new AuthPrincipal(AppRole.USER, user, null));
    }

    private String bearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String token = header.substring(7).trim();
        return token.isBlank() ? null : token;
    }

    String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
