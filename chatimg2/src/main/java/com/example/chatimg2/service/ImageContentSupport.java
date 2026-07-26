package com.example.chatimg2.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ImageContentSupport {

    private ImageContentSupport() {
    }

    public static String detectMediaType(Path path, byte[] bytes) {
        String mediaType = detectMediaType(bytes);
        if (mediaType != null) {
            return mediaType;
        }
        try {
            String probed = Files.probeContentType(path);
            if (probed != null && probed.startsWith("image/")) {
                return probed;
            }
        } catch (IOException ignored) {
            // Byte signatures remain the authoritative check for uploaded images.
        }
        return "image/png";
    }

    public static String detectMediaType(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        if (bytes.length >= 2
                && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8) {
            return "image/jpeg";
        }
        if (bytes.length >= 4 && (bytes[0] & 0xff) == 0x89
                && bytes[1] == 0x50 && bytes[2] == 0x4e && bytes[3] == 0x47) {
            return "image/png";
        }
        if (bytes.length >= 12
                && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            return "image/webp";
        }
        return null;
    }

    public static boolean isSupported(byte[] bytes) {
        return detectMediaType(bytes) != null;
    }

    public static String extension(String mediaType) {
        return switch (mediaType) {
            case "image/jpeg" -> "jpg";
            case "image/webp" -> "webp";
            default -> "png";
        };
    }
}
