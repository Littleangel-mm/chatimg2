package com.example.chatimg2.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ImageContentSupportTest {

    @Test
    void detectsSupportedImageSignatures() {
        assertEquals("image/png", ImageContentSupport.detectMediaType(
                new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a}));
        assertEquals("image/jpeg", ImageContentSupport.detectMediaType(
                new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0}));
        assertEquals("image/webp", ImageContentSupport.detectMediaType(
                new byte[]{'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'}));
    }

    @Test
    void rejectsUnknownOrTruncatedContent() {
        assertNull(ImageContentSupport.detectMediaType(new byte[]{1, 2, 3, 4}));
        assertNull(ImageContentSupport.detectMediaType(new byte[0]));
        assertNull(ImageContentSupport.detectMediaType(null));
    }
}
