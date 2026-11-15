package com.example.chatseedance2.service;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VideoInputPreviewServiceTest {

    @Test
    void blocksNonPublicAddresses() throws Exception {
        assertFalse(VideoInputPreviewService.isPublicAddress(InetAddress.getByName("127.0.0.1")));
        assertFalse(VideoInputPreviewService.isPublicAddress(InetAddress.getByName("10.0.0.1")));
        assertFalse(VideoInputPreviewService.isPublicAddress(InetAddress.getByName("172.16.0.1")));
        assertFalse(VideoInputPreviewService.isPublicAddress(InetAddress.getByName("192.168.1.1")));
        assertFalse(VideoInputPreviewService.isPublicAddress(InetAddress.getByName("169.254.1.1")));
        assertFalse(VideoInputPreviewService.isPublicAddress(InetAddress.getByName("::1")));
        assertFalse(VideoInputPreviewService.isPublicAddress(InetAddress.getByName("fc00::1")));
    }

    @Test
    void acceptsPublicAddresses() throws Exception {
        assertTrue(VideoInputPreviewService.isPublicAddress(InetAddress.getByName("8.8.8.8")));
        assertTrue(VideoInputPreviewService.isPublicAddress(InetAddress.getByName("1.1.1.1")));
        assertTrue(VideoInputPreviewService.isPublicAddress(InetAddress.getByName("2606:4700:4700::1111")));
    }

    @Test
    void detectsSupportedImageSignatures() {
        assertEquals("image/jpeg", VideoInputPreviewService.detectMediaType(
                new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff}));
        assertEquals("image/png", VideoInputPreviewService.detectMediaType(
                new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47}));
        assertEquals("image/webp", VideoInputPreviewService.detectMediaType(
                new byte[]{'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'}));
        assertNull(VideoInputPreviewService.detectMediaType(new byte[]{'<', 'h', 't', 'm', 'l'}));
    }
}
