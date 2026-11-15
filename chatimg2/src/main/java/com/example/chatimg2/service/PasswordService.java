package com.example.chatimg2.service;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

@Service
public class PasswordService {

    private static final int BCRYPT_STRENGTH = 12;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(BCRYPT_STRENGTH);

    public String encode(String rawPassword) {
        if (!isBcryptLengthSupported(rawPassword)) {
            throw new IllegalArgumentException("密码不能超过 72 个字节");
        }
        return encoder.encode(rawPassword);
    }

    public boolean matchesUser(String email, String rawPassword, String storedHash) {
        if (isBcrypt(storedHash)) {
            return isBcryptLengthSupported(rawPassword) && encoder.matches(rawPassword, storedHash);
        }
        return constantTimeEquals(sha256(email + ":" + rawPassword), storedHash);
    }

    public boolean matchesAdmin(String rawPassword, String storedHash) {
        if (isBcrypt(storedHash)) {
            return isBcryptLengthSupported(rawPassword) && encoder.matches(rawPassword, storedHash);
        }
        return constantTimeEquals(sha256(rawPassword), storedHash);
    }

    public boolean needsUpgrade(String storedHash) {
        return !isBcrypt(storedHash);
    }

    public boolean isBcryptLengthSupported(String rawPassword) {
        return rawPassword != null
                && rawPassword.getBytes(StandardCharsets.UTF_8).length <= 72;
    }

    private boolean isBcrypt(String value) {
        return value != null && (value.startsWith("$2a$")
                || value.startsWith("$2b$") || value.startsWith("$2y$"));
    }

    private boolean constantTimeEquals(String expected, String actual) {
        if (actual == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII),
                actual.getBytes(StandardCharsets.US_ASCII));
    }

    private String sha256(String input) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                hex.append(String.format("%02x", value));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
