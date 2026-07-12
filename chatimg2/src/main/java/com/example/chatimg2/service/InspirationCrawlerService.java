package com.example.chatimg2.service;

import com.example.chatimg2.config.ImageStorageConfig;
import com.example.chatimg2.entity.InspirationPrompt;
import com.example.chatimg2.repository.InspirationPromptRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 爬取 img2.ai 灵感画廊接口 /api/photo-prompt-gallery，把 prompt 库落库到本地，
 * 并可选地把媒体下载到 img/upimg 或 img/upvideo 目录实现自托管。
 */
@Slf4j
@Service
public class InspirationCrawlerService {

    private final InspirationPromptRepository repository;
    private final ImageStorageConfig storageConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${app.inspiration.source-url:https://img2.ai/api/photo-prompt-gallery}")
    private String sourceUrl;

    @Value("${app.inspiration.page-size:30}")
    private int pageSize;

    @Value("${app.inspiration.max-pages:100}")
    private int maxPages;

    @Value("${app.inspiration.download-images:true}")
    private boolean downloadImages;

    @Value("${app.inspiration.proxy.enabled:false}")
    private boolean proxyEnabled;

    @Value("${app.inspiration.proxy.type:SOCKS}")
    private String proxyType;

    @Value("${app.inspiration.proxy.host:}")
    private String proxyHost;

    @Value("${app.inspiration.proxy.port:0}")
    private int proxyPort;

    private volatile OkHttpClient httpClient;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Map<String, CrawlResult> lastSummary;
    private volatile long lastFinishedAt;

    public InspirationCrawlerService(InspirationPromptRepository repository, ImageStorageConfig storageConfig) {
        this.repository = repository;
        this.storageConfig = storageConfig;
    }

    public record CrawlResult(String mediaType, int pages, int created, int updated, int downloaded, int failed) {}

    public boolean isRunning() {
        return running.get();
    }

    public Map<String, CrawlResult> getLastSummary() {
        return lastSummary;
    }

    public long getLastFinishedAt() {
        return lastFinishedAt;
    }

    /** 后台异步触发爬取，避免阻塞 HTTP 请求。返回 false 表示已有任务在执行。 */
    public boolean startAsync(String mediaType) {
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        Thread thread = new Thread(() -> {
            try {
                Map<String, CrawlResult> summary = new LinkedHashMap<>();
                if (mediaType == null || mediaType.isBlank()) {
                    summary.putAll(crawlAll());
                } else {
                    summary.put(mediaType, crawl(mediaType));
                }
                lastSummary = summary;
                lastFinishedAt = System.currentTimeMillis();
            } catch (Exception e) {
                log.error("Inspiration crawl task failed", e);
            } finally {
                running.set(false);
            }
        }, "inspiration-crawl");
        thread.setDaemon(true);
        thread.start();
        return true;
    }

    /** 爬取图片 + 视频两种类型 */
    public Map<String, CrawlResult> crawlAll() {
        Map<String, CrawlResult> all = new LinkedHashMap<>();
        all.put("image", crawl("image"));
        all.put("video", crawl("video"));
        return all;
    }

    public CrawlResult crawl(String mediaType) {
        String type = "video".equals(mediaType) ? "video" : "image";
        int created = 0, updated = 0, downloaded = 0, failed = 0, page = 1;
        boolean hasMore = true;

        log.info("Inspiration crawl start: mediaType={}, proxyEnabled={}, proxy={}:{}",
                type, proxyEnabled, proxyHost, proxyPort);
        if (!proxyEnabled) {
            log.warn("代理未启用（INSPIRATION_PROXY_ENABLED=false）。若本机无法直连 img2.ai，请在 .env 开启代理并重启应用。");
        }
        while (hasMore && page <= maxPages) {
            JsonNode data;
            try {
                data = fetchPage(type, page);
            } catch (Exception e) {
                log.error("Inspiration crawl fetch failed at page {}: {}", page, e.getMessage());
                failed++;
                break;
            }

            JsonNode items = data.get("items");
            if (items == null || !items.isArray() || items.isEmpty()) {
                break;
            }

            int indexInPage = 0;
            for (JsonNode item : items) {
                String externalId = text(item, "id");
                String prompt = text(item, "prompt");
                String remoteUrl = resolveMediaUrl(item, type);
                if ((remoteUrl == null || remoteUrl.isBlank()) && indexInPage == 0) {
                    log.warn("Inspiration item has no downloadable {} url. fields={}", type, fieldNames(item));
                }
                if (prompt == null || prompt.isBlank()) {
                    continue;
                }

                int sortOrder = (page - 1) * pageSize + indexInPage;
                boolean isNew = externalId == null || !repository.existsByExternalId(externalId);

                InspirationPrompt entity = (externalId != null)
                        ? repository.findByExternalId(externalId).orElseGet(InspirationPrompt::new)
                        : new InspirationPrompt();

                entity.setExternalId(externalId);
                entity.setMediaType(type);
                entity.setCategory(text(item, "category"));
                entity.setSubcategory(text(item, "subcategory"));
                entity.setSourceUrl(remoteUrl);
                entity.setPrompt(prompt);
                entity.setSortOrder(sortOrder);

                String localOrRemote = remoteUrl;
                if (downloadImages && remoteUrl != null && !remoteUrl.isBlank()) {
                    String saved = tryDownloadMedia(remoteUrl, externalId, type, item, sortOrder);
                    if (saved != null) {
                        localOrRemote = saved;
                        downloaded++;
                    } else {
                        failed++;
                        localOrRemote = remoteUrl;
                    }
                }
                entity.setImageUrl(localOrRemote);

                repository.save(entity);
                if (isNew) {
                    created++;
                } else {
                    updated++;
                }
                indexInPage++;
            }

            hasMore = data.path("hasMore").asBoolean(false);
            page++;
        }

        CrawlResult result = new CrawlResult(type, page - 1, created, updated, downloaded, failed);
        log.info("Inspiration crawl done: {}", result);
        return result;
    }

    private JsonNode fetchPage(String mediaType, int page) throws IOException {
        HttpUrl base = HttpUrl.parse(sourceUrl);
        if (base == null) {
            throw new IOException("非法的来源地址: " + sourceUrl);
        }
        HttpUrl url = base.newBuilder()
                .addQueryParameter("page", String.valueOf(page))
                .addQueryParameter("pageSize", String.valueOf(pageSize))
                .addQueryParameter("mediaType", mediaType)
                .build();

        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0")
                .header("Accept", "application/json")
                .get()
                .build();

        try (Response response = client().newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + ": " + truncate(body));
            }
            JsonNode root = objectMapper.readTree(body);
            JsonNode data = root.get("data");
            if (data == null || data.isNull()) {
                throw new IOException("响应缺少 data 字段: " + truncate(body));
            }
            return data;
        }
    }

    /** 下载媒体到 img/upimg 或 img/upvideo，返回前端相对路径，失败返回 null */
    private String tryDownloadMedia(String remoteUrl, String externalId, String mediaType,
                                    JsonNode item, int sortOrder) {
        try {
            String folder = "video".equals(mediaType) ? "upvideo" : "upimg";
            Path dir = "video".equals(mediaType) ? storageConfig.getUpvideoDir() : storageConfig.getUpimgDir();
            Files.createDirectories(dir);

            String normalizedUrl = normalizeRemoteUrl(remoteUrl);
            String ext = extractExtension(normalizedUrl, mediaType);
            String baseName = resolveBaseName(externalId, remoteUrl);
            String fileName = baseName + ext;
            Path target = dir.resolve(fileName);

            Request request = new Request.Builder()
                    .url(normalizedUrl)
                    .header("User-Agent", "Mozilla/5.0")
                    .header("Accept", "video/*,image/*,*/*")
                    .header("Referer", "https://img2.ai/")
                    .get()
                    .build();
            try (Response response = client().newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    String body = response.body() != null ? truncate(response.body().string()) : "";
                    log.warn("Download inspiration media failed {}: HTTP {}, contentType={}, body={}",
                            normalizedUrl, response.code(), response.header("Content-Type"), body);
                    return null;
                }
                String contentType = response.header("Content-Type");
                if (isTextResponse(contentType)) {
                    String body = truncate(response.body().string());
                    log.warn("Download inspiration media skipped {}: unexpected contentType={}, body={}",
                            normalizedUrl, contentType, body);
                    return null;
                }
                try (InputStream is = response.body().byteStream()) {
                    Files.copy(is, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            String localPath = folder + "/" + fileName;
            writePromptJson(dir.resolve(baseName + ".json"), item, mediaType, remoteUrl, localPath, sortOrder);
            log.info("Downloaded inspiration media: {} -> img/{}", remoteUrl, localPath);
            return localPath;
        } catch (Exception e) {
            log.warn("Download inspiration media error {}: {}", remoteUrl, e.getMessage());
            return null;
        }
    }

    private static String resolveBaseName(String externalId, String remoteUrl) {
        String raw = externalId != null && !externalId.isBlank()
                ? externalId
                : Integer.toUnsignedString(remoteUrl.hashCode(), 36);
        return raw.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String resolveMediaUrl(JsonNode item, String mediaType) {
        if ("video".equals(mediaType)) {
            String video = firstText(item, "videoUrl", "video_url", "video", "mediaUrl", "media_url",
                    "fileUrl", "file_url", "downloadUrl", "download_url", "url", "src", "sourceUrl", "source_url");
            if (video != null && !video.isBlank()) {
                return video;
            }
        }
        String image = firstText(item, "imageUrl", "image_url", "image", "coverUrl", "cover_url",
                "thumbnailUrl", "thumbnail_url", "thumbUrl", "thumb_url", "mediaUrl", "media_url",
                "fileUrl", "file_url", "downloadUrl", "download_url", "url", "src", "sourceUrl", "source_url");
        if (image != null && !image.isBlank()) {
            return image;
        }
        return findNestedMediaUrl(item, mediaType, 0);
    }

    private void writePromptJson(Path jsonPath, JsonNode item, String mediaType, String sourceUrl,
                                 String localPath, int sortOrder) throws IOException {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("externalId", text(item, "id"));
        data.put("mediaType", mediaType);
        data.put("category", text(item, "category"));
        data.put("subcategory", text(item, "subcategory"));
        data.put("prompt", text(item, "prompt"));
        data.put("sourceUrl", sourceUrl);
        data.put("localPath", localPath);
        data.put("sortOrder", sortOrder);
        data.put("crawledAt", System.currentTimeMillis());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(jsonPath.toFile(), data);
    }

    private OkHttpClient client() {
        if (httpClient != null) {
            return httpClient;
        }
        synchronized (this) {
            if (httpClient == null) {
                OkHttpClient.Builder builder = new OkHttpClient.Builder()
                        .connectTimeout(30, TimeUnit.SECONDS)
                        .readTimeout(60, TimeUnit.SECONDS);
                if (proxyEnabled && proxyHost != null && !proxyHost.isBlank() && proxyPort > 0) {
                    Proxy.Type type = "HTTP".equalsIgnoreCase(proxyType) ? Proxy.Type.HTTP : Proxy.Type.SOCKS;
                    builder.proxy(new Proxy(type, new InetSocketAddress(proxyHost, proxyPort)));
                    log.info("Inspiration crawler using proxy {} {}:{}", type, proxyHost, proxyPort);
                }
                httpClient = builder.build();
            }
        }
        return httpClient;
    }

    private static String extractExtension(String url, String mediaType) {
        try {
            String path = HttpUrl.parse(url) != null ? HttpUrl.parse(url).encodedPath() : url;
            int dot = path.lastIndexOf('.');
            if (dot >= 0 && dot >= path.length() - 6) {
                String ext = path.substring(dot).toLowerCase();
                if (ext.matches("\\.[a-z0-9]{2,5}")) {
                    return ext;
                }
            }
        } catch (Exception ignored) {
        }
        return "video".equals(mediaType) ? ".mp4" : ".webp";
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && !value.isNull() ? value.asText() : null;
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = text(node, field);
            if (value != null && !value.isBlank()) {
                if (isHttpUrl(value)) {
                    return value;
                }
                String nested = extractUrlFromTextOrNode(value, node.get(field), null);
                if (nested != null && !nested.isBlank()) {
                    return nested;
                }
            }
        }
        return null;
    }

    private static String findNestedMediaUrl(JsonNode node, String mediaType, int depth) {
        if (node == null || node.isNull() || depth > 4) {
            return null;
        }
        String direct = extractUrlFromTextOrNode(node.asText(null), node, mediaType);
        if (direct != null) {
            return direct;
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String fieldName = entry.getKey().toLowerCase();
                JsonNode value = entry.getValue();
                if (looksLikeMediaField(fieldName, mediaType)) {
                    String url = extractUrlFromTextOrNode(value.asText(null), value, mediaType);
                    if (url != null) {
                        return url;
                    }
                }
                String nested = findNestedMediaUrl(value, mediaType, depth + 1);
                if (nested != null) {
                    return nested;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                String nested = findNestedMediaUrl(child, mediaType, depth + 1);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    private static String extractUrlFromTextOrNode(String text, JsonNode node, String mediaType) {
        if (text != null && looksLikeDownloadUrl(text, mediaType)) {
            return text;
        }
        if (node != null && node.isObject()) {
            String value = firstText(node, "url", "src", "href", "downloadUrl", "download_url",
                    "fileUrl", "file_url", "mediaUrl", "media_url");
            if (value != null && looksLikeDownloadUrl(value, mediaType)) {
                return value;
            }
        }
        return null;
    }

    private static boolean looksLikeMediaField(String fieldName, String mediaType) {
        if ("video".equals(mediaType) && fieldName.contains("video")) {
            return true;
        }
        if (!"video".equals(mediaType)
                && (fieldName.contains("image") || fieldName.contains("cover") || fieldName.contains("thumb"))) {
            return true;
        }
        return fieldName.contains("media") || fieldName.contains("file") || fieldName.contains("download")
                || fieldName.equals("url") || fieldName.equals("src");
    }

    private static boolean isHttpUrl(String value) {
        if (value == null) {
            return false;
        }
        String lower = value.toLowerCase();
        return lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("//");
    }

    private static boolean looksLikeDownloadUrl(String value, String mediaType) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String lower = value.toLowerCase();
        if (!(lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("//"))) {
            return false;
        }
        if ("video".equals(mediaType)) {
            return lower.contains(".mp4") || lower.contains(".webm") || lower.contains(".mov")
                    || lower.contains("video") || lower.contains("media");
        }
        return lower.contains(".png") || lower.contains(".jpg") || lower.contains(".jpeg")
                || lower.contains(".webp") || lower.contains(".gif") || lower.contains("image")
                || lower.contains("cover") || lower.contains("thumbnail");
    }

    private static boolean isTextResponse(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return false;
        }
        String lower = contentType.toLowerCase();
        return lower.startsWith("text/") || lower.contains("json") || lower.contains("xml");
    }

    private static String normalizeRemoteUrl(String remoteUrl) {
        if (remoteUrl != null && remoteUrl.startsWith("//")) {
            return "https:" + remoteUrl;
        }
        URI uri = URI.create(remoteUrl);
        if (uri.getScheme() == null) {
            throw new IllegalArgumentException("媒体地址不是完整 URL: " + remoteUrl);
        }
        return remoteUrl;
    }

    private static String fieldNames(JsonNode node) {
        if (node == null || !node.isObject()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            if (!builder.isEmpty()) {
                builder.append(',');
            }
            builder.append(names.next());
        }
        return builder.toString();
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 200 ? text.substring(0, 200) : text;
    }
}
