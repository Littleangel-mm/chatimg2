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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 爬取 img2.ai 灵感画廊接口 /api/photo-prompt-gallery，把 prompt 库落库到本地，
 * 并可选地把图片下载到 img/inspiration 目录实现自托管。
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

        log.info("Inspiration crawl start: mediaType={}", type);
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
                String remoteUrl = text(item, "imageUrl");
                if (prompt == null || prompt.isBlank()) {
                    indexInPage++;
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
                    String saved = tryDownloadImage(remoteUrl, externalId, type);
                    if (saved != null) {
                        localOrRemote = saved;
                        downloaded++;
                    } else {
                        failed++;
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

    /** 下载图片到 img/inspiration 目录，返回前端相对路径 inspiration/<name>，失败返回 null */
    private String tryDownloadImage(String remoteUrl, String externalId, String mediaType) {
        try {
            Path dir = storageConfig.getBackupDir().resolve("inspiration");
            Files.createDirectories(dir);

            String ext = extractExtension(remoteUrl);
            String baseName = (externalId != null && !externalId.isBlank())
                    ? externalId
                    : Integer.toHexString(remoteUrl.hashCode());
            String fileName = mediaType + "_" + baseName + ext;
            Path target = dir.resolve(fileName);

            if (Files.exists(target) && Files.size(target) > 0) {
                return "inspiration/" + fileName;
            }

            Request request = new Request.Builder()
                    .url(remoteUrl)
                    .header("User-Agent", "Mozilla/5.0")
                    .get()
                    .build();
            try (Response response = client().newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    log.warn("Download inspiration image failed {}: HTTP {}", remoteUrl, response.code());
                    return null;
                }
                try (InputStream is = response.body().byteStream()) {
                    Files.copy(is, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            return "inspiration/" + fileName;
        } catch (Exception e) {
            log.warn("Download inspiration image error {}: {}", remoteUrl, e.getMessage());
            return null;
        }
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

    private static String extractExtension(String url) {
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
        return ".webp";
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && !value.isNull() ? value.asText() : null;
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 200 ? text.substring(0, 200) : text;
    }
}
