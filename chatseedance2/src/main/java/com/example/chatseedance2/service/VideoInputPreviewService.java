package com.example.chatseedance2.service;

import com.example.chatseedance2.config.VideoProperties;
import com.example.chatseedance2.entity.VideoGenerationAsset;
import com.example.chatseedance2.exception.BusinessException;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class VideoInputPreviewService {

    private static final int MAX_REDIRECTS = 3;

    private final OkHttpClient seedanceHttpClient;
    private final VideoProperties properties;
    private final VideoStorageService storageService;
    private OkHttpClient previewClient;

    @PostConstruct
    void initialize() {
        previewClient = seedanceHttpClient.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .dns(this::resolvePublicAddresses)
                .build();
    }

    public PreviewImage load(VideoGenerationAsset asset) {
        if (asset.getLocalPath() != null && !asset.getLocalPath().isBlank()) {
            return loadLocal(asset.getLocalPath());
        }
        if (asset.getSourceUrl() == null || asset.getSourceUrl().isBlank()) {
            throw new BusinessException("INPUT_IMAGE_URL_MISSING", "视频参考图地址为空");
        }
        return loadRemote(asset.getSourceUrl());
    }

    private PreviewImage loadLocal(String localPath) {
        Path path = storageService.resolve(localPath);
        try {
            long size = Files.size(path);
            validateSize(size);
            byte[] bytes = Files.readAllBytes(path);
            return validatedImage(bytes);
        } catch (IOException e) {
            throw new BusinessException("INPUT_IMAGE_READ_FAILED", "读取视频参考图失败", e);
        }
    }

    private PreviewImage loadRemote(String sourceUrl) {
        URI current = validateRemoteUri(sourceUrl);
        for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
            Request request = new Request.Builder()
                    .url(current.toString())
                    .header("Accept", "image/png,image/jpeg,image/webp,image/*;q=0.8")
                    .header("User-Agent", "ChatImg2-Image-Preview/1.0")
                    .build();
            try (Response response = previewClient.newCall(request).execute()) {
                if (isRedirect(response.code())) {
                    String location = response.header("Location");
                    if (location == null || redirect == MAX_REDIRECTS) {
                        throw new BusinessException("INPUT_IMAGE_REDIRECT_INVALID", "视频参考图重定向无效");
                    }
                    current = validateRemoteUri(current.resolve(location).toString());
                    continue;
                }
                if (!response.isSuccessful() || response.body() == null) {
                    throw new BusinessException("INPUT_IMAGE_FETCH_FAILED",
                            "视频参考图加载失败: HTTP " + response.code());
                }
                long contentLength = response.body().contentLength();
                if (contentLength > 0) validateSize(contentLength);
                try (InputStream input = response.body().byteStream()) {
                    return validatedImage(readWithLimit(input));
                }
            } catch (BusinessException e) {
                throw e;
            } catch (IOException e) {
                throw new BusinessException("INPUT_IMAGE_FETCH_FAILED", "视频参考图网络加载失败", e);
            }
        }
        throw new BusinessException("INPUT_IMAGE_REDIRECT_INVALID", "视频参考图重定向次数过多");
    }

    private URI validateRemoteUri(String value) {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException e) {
            throw new BusinessException("INPUT_IMAGE_URL_INVALID", "视频参考图地址无效", e);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!("http".equals(scheme) || "https".equals(scheme))
                || uri.getHost() == null || uri.getUserInfo() != null) {
            throw new BusinessException("INPUT_IMAGE_URL_INVALID", "视频参考图地址无效");
        }
        return uri;
    }

    private List<InetAddress> resolvePublicAddresses(String host) throws UnknownHostException {
        List<InetAddress> addresses = Arrays.asList(InetAddress.getAllByName(host));
        if (addresses.isEmpty() || addresses.stream().anyMatch(address -> !isPublicAddress(address))) {
            throw new UnknownHostException("Blocked non-public image host");
        }
        return addresses;
    }

    static boolean isPublicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            return first != 0 && first != 10 && first != 127 && first < 224
                    && !(first == 100 && second >= 64 && second <= 127)
                    && !(first == 169 && second == 254)
                    && !(first == 172 && second >= 16 && second <= 31)
                    && !(first == 192 && second == 168)
                    && !(first == 198 && (second == 18 || second == 19));
        }
        return bytes.length != 16 || (bytes[0] & 0xfe) != 0xfc;
    }

    private byte[] readWithLimit(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            if ((long) output.size() + read > properties.maxImageBytes()) {
                throw new BusinessException("INPUT_IMAGE_TOO_LARGE", "视频参考图超过大小限制");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private void validateSize(long size) {
        if (size <= 0 || size > properties.maxImageBytes()) {
            throw new BusinessException("INPUT_IMAGE_TOO_LARGE", "视频参考图为空或超过大小限制");
        }
    }

    private PreviewImage validatedImage(byte[] bytes) {
        String mediaType = detectMediaType(bytes);
        if (mediaType == null) {
            throw new BusinessException("INPUT_IMAGE_TYPE_INVALID", "视频参考图不是支持的图片格式");
        }
        return new PreviewImage(bytes, mediaType);
    }

    static String detectMediaType(byte[] bytes) {
        if (bytes == null) return null;
        if (bytes.length >= 3 && (bytes[0] & 0xff) == 0xff
                && (bytes[1] & 0xff) == 0xd8 && (bytes[2] & 0xff) == 0xff) {
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

    private boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    public record PreviewImage(byte[] bytes, String mediaType) {
    }
}
