package com.example.chatimg2.service;

import com.example.chatimg2.config.ImageStorageConfig;
import com.example.chatimg2.entity.InspirationPrompt;
import com.example.chatimg2.repository.InspirationPromptRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
@Service
@RequiredArgsConstructor
public class InspirationMediaService {

    private final InspirationPromptRepository repository;
    private final ImageStorageConfig storageConfig;

    public void streamMedia(Integer id, HttpServletRequest request, HttpServletResponse response) throws IOException {
        InspirationPrompt prompt = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "灵感不存在"));

        Path local = resolveLocalFile(prompt);
        if (local != null && Files.isRegularFile(local)) {
            streamLocalFile(local, request, response);
            return;
        }

        String remoteUrl = resolveRemoteUrl(prompt);
        if (remoteUrl == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "媒体文件不存在");
        }
        redirectToRemote(remoteUrl, response);
    }

    /** 视频展示地址：本地有文件走同源接口，否则直接返回远程 URL 由浏览器加载 */
    public String resolveVideoDisplayUrl(InspirationPrompt prompt) {
        Path local = resolveLocalFile(prompt);
        if (local != null && Files.isRegularFile(local)) {
            return mediaApiPath(prompt.getId());
        }
        String remote = resolveRemoteUrl(prompt);
        if (remote != null && !remote.isBlank()) {
            return remote;
        }
        return prompt.getImageUrl();
    }

    public String mediaApiPath(Integer id) {
        return "/api/inspiration/media/" + id;
    }

    private void redirectToRemote(String remoteUrl, HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_FOUND);
        response.setHeader(HttpHeaders.LOCATION, remoteUrl);
    }

    private Path resolveLocalFile(InspirationPrompt prompt) {
        String imageUrl = prompt.getImageUrl();
        if (imageUrl == null || imageUrl.isBlank()) {
            return null;
        }
        if (imageUrl.startsWith("http://") || imageUrl.startsWith("https://")) {
            return null;
        }
        return storageConfig.getBackupDir().resolve(imageUrl.replace('\\', '/'));
    }

    private String resolveRemoteUrl(InspirationPrompt prompt) {
        if (prompt.getSourceUrl() != null && !prompt.getSourceUrl().isBlank()) {
            return prompt.getSourceUrl();
        }
        String imageUrl = prompt.getImageUrl();
        if (imageUrl != null && (imageUrl.startsWith("http://") || imageUrl.startsWith("https://"))) {
            return imageUrl;
        }
        return null;
    }

    private void streamLocalFile(Path file, HttpServletRequest request, HttpServletResponse response) throws IOException {
        long fileLength = Files.size(file);
        String contentType = probeContentType(file);
        response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
        response.setContentType(contentType);

        String rangeHeader = request.getHeader(HttpHeaders.RANGE);
        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            long[] range = parseRange(rangeHeader, fileLength);
            if (range == null) {
                response.setStatus(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
                response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes */" + fileLength);
                return;
            }
            long start = range[0];
            long end = range[1];
            long contentLength = end - start + 1;
            response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
            response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + fileLength);
            response.setHeader(HttpHeaders.CONTENT_LENGTH, String.valueOf(contentLength));
            try (InputStream in = Files.newInputStream(file); OutputStream out = response.getOutputStream()) {
                in.skipNBytes(start);
                in.transferTo(out);
            }
            return;
        }

        response.setStatus(HttpServletResponse.SC_OK);
        response.setHeader(HttpHeaders.CONTENT_LENGTH, String.valueOf(fileLength));
        try (InputStream in = Files.newInputStream(file); OutputStream out = response.getOutputStream()) {
            in.transferTo(out);
        }
    }

    private static long[] parseRange(String rangeHeader, long fileLength) {
        try {
            String spec = rangeHeader.substring("bytes=".length()).trim();
            int dash = spec.indexOf('-');
            if (dash < 0) {
                return null;
            }
            String startPart = spec.substring(0, dash);
            String endPart = spec.substring(dash + 1);
            long start;
            long end;
            if (startPart.isEmpty()) {
                long suffix = Long.parseLong(endPart);
                start = Math.max(0, fileLength - suffix);
                end = fileLength - 1;
            } else {
                start = Long.parseLong(startPart);
                end = endPart.isEmpty() ? fileLength - 1 : Long.parseLong(endPart);
            }
            if (start > end || start >= fileLength) {
                return null;
            }
            end = Math.min(end, fileLength - 1);
            return new long[] { start, end };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String probeContentType(Path file) {
        try {
            String probed = Files.probeContentType(file);
            if (probed != null && !probed.isBlank()) {
                return probed;
            }
        } catch (IOException ignored) {
        }
        String name = file.getFileName().toString().toLowerCase();
        if (name.endsWith(".mp4")) {
            return "video/mp4";
        }
        if (name.endsWith(".webm")) {
            return "video/webm";
        }
        if (name.endsWith(".mov")) {
            return "video/quicktime";
        }
        if (name.endsWith(".webp")) {
            return "image/webp";
        }
        if (name.endsWith(".png")) {
            return "image/png";
        }
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        return "application/octet-stream";
    }
}
