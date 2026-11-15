package com.example.chatimg2.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordServiceTest {

    private final PasswordService passwordService = new PasswordService();

    @Test
    void bcryptPasswordsAreVerified() {
        String encoded = passwordService.encode("secret123");

        assertTrue(passwordService.matchesUser("user@qq.com", "secret123", encoded));
        assertFalse(passwordService.matchesUser("user@qq.com", "wrong", encoded));
        assertFalse(passwordService.needsUpgrade(encoded));
    }

    @Test
    void legacyUserAndAdminHashesRemainCompatible() throws Exception {
        String legacyUser = sha256("user@qq.com:secret123");
        String legacyAdmin = sha256("admin123");

        assertTrue(passwordService.matchesUser("user@qq.com", "secret123", legacyUser));
        assertTrue(passwordService.matchesAdmin("admin123", legacyAdmin));
        assertTrue(passwordService.needsUpgrade(legacyUser));
    }

    @Test
    void bcryptLengthLimitIsEnforcedBeforeHashing() {
        assertFalse(passwordService.isBcryptLengthSupported("密".repeat(25)));
        assertTrue(passwordService.isBcryptLengthSupported("a".repeat(72)));
    }

    private String sha256(String value) throws Exception {
        byte[] bytes = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte item : bytes) {
            hex.append(String.format("%02x", item));
        }
        return hex.toString();
    }
}
