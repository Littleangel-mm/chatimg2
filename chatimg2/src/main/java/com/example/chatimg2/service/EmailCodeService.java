package com.example.chatimg2.service;

import com.example.chatimg2.entity.EmailVerificationCode;
import com.example.chatimg2.repository.EmailVerificationCodeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class EmailCodeService {

    private final EmailVerificationCodeRepository codeRepository;
    private final JavaMailSender mailSender;
    private final AuthService authService;

    @Value("${app.mail.from:${spring.mail.username:}}")
    private String from;

    @Value("${spring.mail.username:}")
    private String mailUsername;

    @Value("${spring.mail.password:}")
    private String mailPassword;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration SEND_INTERVAL = Duration.ofSeconds(60);
    private static final Duration CODE_TTL = Duration.ofMinutes(5);
    private static final int MAX_ATTEMPTS = 5;

    @Transactional
    public void sendCode(String email, String scene, String requestIp) {
        String normalized = authService.normalizeEmail(email);
        String normalizedScene = normalizeScene(scene);
        if (!authService.isQqEmail(normalized)) {
            throw new RuntimeException("请输入有效的 QQ 邮箱");
        }

        codeRepository.findTopByEmailAndSceneAndUsedFalseOrderByCreatedAtDesc(normalized, normalizedScene)
                .filter(code -> code.getCreatedAt() != null)
                .filter(code -> code.getCreatedAt().plus(SEND_INTERVAL).isAfter(LocalDateTime.now()))
                .ifPresent(code -> {
                    throw new RuntimeException("验证码发送太频繁，请稍后再试");
                });

        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        EmailVerificationCode entity = new EmailVerificationCode();
        entity.setEmail(normalized);
        entity.setScene(normalizedScene);
        entity.setCodeHash(hashCode(normalized, normalizedScene, code));
        entity.setExpiresAt(LocalDateTime.now().plus(CODE_TTL));
        entity.setRequestIp(requestIp);
        codeRepository.save(entity);

        String sender = from == null || from.isBlank() ? mailUsername : from;
        if (sender == null || sender.isBlank() || mailPassword == null || mailPassword.isBlank()) {
            throw new RuntimeException("请先配置 QQ 邮箱 SMTP：MAIL_USERNAME 和 MAIL_PASSWORD");
        }

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(sender.trim());
        message.setTo(normalized);
        message.setSubject("ChatImg2 验证码");
        message.setText("你的 ChatImg2 验证码是：" + code + "\n\n5 分钟内有效，请勿转发给他人。");
        mailSender.send(message);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void verify(String email, String scene, String code) {
        String normalized = authService.normalizeEmail(email);
        String normalizedScene = normalizeScene(scene);
        if (code == null || !code.matches("^\\d{6}$")) {
            throw new RuntimeException("请输入 6 位验证码");
        }

        EmailVerificationCode entity = codeRepository
                .findLatestUnusedForUpdate(normalized, normalizedScene)
                .orElseThrow(() -> new RuntimeException("验证码不存在或已过期"));

        if (entity.getExpiresAt().isBefore(LocalDateTime.now())) {
            entity.setUsed(true);
            entity.setUsedAt(LocalDateTime.now());
            codeRepository.save(entity);
            throw new RuntimeException("验证码已过期");
        }
        if (entity.getAttempts() >= MAX_ATTEMPTS) {
            entity.setUsed(true);
            entity.setUsedAt(LocalDateTime.now());
            codeRepository.save(entity);
            throw new RuntimeException("验证码错误次数过多，请重新获取");
        }

        String expected = hashCode(normalized, normalizedScene, code);
        if (!expected.equals(entity.getCodeHash())) {
            entity.setAttempts(entity.getAttempts() + 1);
            codeRepository.save(entity);
            throw new RuntimeException("验证码错误");
        }

        entity.setUsed(true);
        entity.setUsedAt(LocalDateTime.now());
        codeRepository.save(entity);
    }

    private String normalizeScene(String scene) {
        String normalized = scene == null ? "" : scene.trim().toUpperCase();
        if (!"REGISTER".equals(normalized) && !"LOGIN".equals(normalized)) {
            throw new RuntimeException("验证码场景不正确");
        }
        return normalized;
    }

    private String hashCode(String email, String scene, String code) {
        return authService.sha256(email + ":" + scene + ":" + code);
    }
}
