package com.example.chatimg2.service;

import com.example.chatimg2.config.ImageStorageConfig;
import com.example.chatimg2.entity.InspirationPrompt;
import com.example.chatimg2.repository.InspirationPromptRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 爬取 img2.ai 灵感画廊接口 /api/photo-prompt-gallery，把 prompt 库落库到本地，
 * 并可选地把媒体下载到 img/upimg 或 img/upvideo 目录实现自托管。
 */
@Slf4j
@Service
public class InspirationCrawlerService {

    private static final String PROMPT_MANIFEST_NAME = "prompts.json";

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

    @Value("${app.inspiration.max-media-bytes:524288000}")
    private long maxMediaBytes;

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
        logSystemProxyChoice();
        cleanupLegacyPromptJson(type);
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
        Path target = null;
        try {
            String folder = "video".equals(mediaType) ? "upvideo" : "upimg";
            Path dir = "video".equals(mediaType) ? storageConfig.getUpvideoDir() : storageConfig.getUpimgDir();
            Files.createDirectories(dir);

            String normalizedUrl = normalizeRemoteUrl(remoteUrl);
            String ext = extractExtension(normalizedUrl, mediaType);
            String baseName = resolveBaseName(externalId, remoteUrl);
            String fileName = baseName + ext;
            target = dir.resolve(fileName);

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
                long contentLength = response.body().contentLength();
                if (contentLength > maxMediaBytes) {
                    log.warn("Inspiration media exceeds limit {} bytes: {}", maxMediaBytes, normalizedUrl);
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
                    copyWithLimit(is, target, maxMediaBytes);
                }
            }
            String localPath = folder + "/" + fileName;
            writePromptManifest(dir, item, mediaType, remoteUrl, localPath, sortOrder);
            log.info("Downloaded inspiration media: {} -> img/{}", remoteUrl, localPath);
            return localPath;
        } catch (Exception e) {
            try {
                Files.deleteIfExists(target);
            } catch (IOException cleanupError) {
                log.debug("Cleanup incomplete inspiration media failed {}: {}",
                        target.getFileName(), cleanupError.getMessage());
            }
            log.warn("Download inspiration media error {}: {}", remoteUrl, e.getMessage());
            return null;
        }
    }

    private static void copyWithLimit(InputStream input, Path target, long maxBytes) throws IOException {
        long total = 0;
        byte[] buffer = new byte[8192];
        try (var output = Files.newOutputStream(target)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw new IOException("media exceeds configured size limit");
                }
                output.write(buffer, 0, read);
            }
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

    private void cleanupLegacyPromptJson(String mediaType) {
        Path dir = "video".equals(mediaType) ? storageConfig.getUpvideoDir() : storageConfig.getUpimgDir();
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var files = Files.list(dir)) {
            long deleted = files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".json"))
                    .filter(path -> !PROMPT_MANIFEST_NAME.equals(path.getFileName().toString()))
                    .map(path -> {
                        try {
                            Files.deleteIfExists(path);
                            return 1L;
                        } catch (IOException e) {
                            log.warn("Delete legacy inspiration prompt json failed {}: {}", path.getFileName(), e.getMessage());
                            return 0L;
                        }
                    })
                    .reduce(0L, Long::sum);
            if (deleted > 0) {
                log.info("Deleted {} legacy inspiration prompt json files from {}", deleted, dir);
            }
        } catch (IOException e) {
            log.warn("Cleanup legacy inspiration prompt json failed: {}", e.getMessage());
        }
    }

    private void writePromptManifest(Path dir, JsonNode item, String mediaType, String sourceUrl,
                                     String localPath, int sortOrder) throws IOException {
        Path manifestPath = dir.resolve(PROMPT_MANIFEST_NAME);
        Map<String, JsonNode> entries = readPromptManifestItems(manifestPath);
        ObjectNode data = objectMapper.createObjectNode();
        data.put("externalId", text(item, "id"));
        data.put("mediaType", mediaType);
        data.put("category", text(item, "category"));
        data.put("subcategory", text(item, "subcategory"));
        data.put("prompt", text(item, "prompt"));
        data.put("sourceUrl", sourceUrl);
        data.put("localPath", localPath);
        data.put("sortOrder", sortOrder);
        data.put("crawledAt", System.currentTimeMillis());
        entries.put(manifestKey(data), data);

        ObjectNode root = objectMapper.createObjectNode();
        root.put("mediaType", mediaType);
        root.put("updatedAt", System.currentTimeMillis());
        ArrayNode items = root.putArray("items");
        entries.values().forEach(items::add);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(manifestPath.toFile(), root);
    }

    private Map<String, JsonNode> readPromptManifestItems(Path manifestPath) {
        Map<String, JsonNode> entries = new LinkedHashMap<>();
        if (!Files.isRegularFile(manifestPath)) {
            return entries;
        }
        try {
            JsonNode root = objectMapper.readTree(manifestPath.toFile());
            JsonNode items = root.get("items");
            if (items != null && items.isArray()) {
                for (JsonNode item : items) {
                    entries.put(manifestKey(item), item);
                }
            }
        } catch (Exception e) {
            log.warn("Read inspiration prompt manifest failed {}: {}", manifestPath.getFileName(), e.getMessage());
        }
        return entries;
    }

    private static String manifestKey(JsonNode item) {
        String externalId = text(item, "externalId");
        if (externalId != null && !externalId.isBlank()) {
            return "external:" + externalId;
        }
        String localPath = text(item, "localPath");
        if (localPath != null && !localPath.isBlank()) {
            return "local:" + localPath;
        }
        return "item:" + item.hashCode();
    }

    private OkHttpClient client() {
        if (httpClient != null) {
            return httpClient;
        }
        synchronized (this) {
            if (httpClient == null) {
                Proxy effectiveProxy = resolveEffectiveProxy();
                ProxySelector proxySelector = ProxySelector.getDefault();
                OkHttpClient.Builder builder = new OkHttpClient.Builder()
                        .connectTimeout(30, TimeUnit.SECONDS)
                        .readTimeout(60, TimeUnit.SECONDS);
                if (isProxy(effectiveProxy)) {
                    builder.proxy(effectiveProxy);
                } else if (proxySelector != null) {
                    builder.proxySelector(proxySelector);
                }
                httpClient = builder.build();
            }
        }
        return httpClient;
    }

    private void logSystemProxyChoice() {
        try {
            String route = describeProxy(resolveEffectiveProxy());
            log.info("Inspiration crawler system network route: {}", route);
        } catch (Exception e) {
            log.info("Inspiration crawler system network route check failed: {}", e.getMessage());
        }
    }

    private Proxy resolveEffectiveProxy() {
        Proxy systemProxy = resolveJavaSystemProxy().orElse(Proxy.NO_PROXY);
        if (isProxy(systemProxy)) {
            return systemProxy;
        }
        return resolveWindowsProxyFallback().orElse(Proxy.NO_PROXY);
    }

    private Optional<Proxy> resolveJavaSystemProxy() {
        try {
            ProxySelector selector = ProxySelector.getDefault();
            HttpUrl parsed = HttpUrl.parse(sourceUrl);
            if (selector == null || parsed == null) {
                return Optional.empty();
            }
            URI uri = parsed.uri();
            List<Proxy> proxies = selector.select(uri);
            if (proxies == null) {
                return Optional.empty();
            }
            return proxies.stream().filter(this::isProxy).findFirst();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private Optional<Proxy> resolveWindowsProxyFallback() {
        if (!isWindows()) {
            return Optional.empty();
        }
        String proxyServer = readWindowsInternetSetting("ProxyServer");
        if (proxyServer == null || proxyServer.isBlank()) {
            return Optional.empty();
        }
        return parseProxyServer(proxyServer);
    }

    private Optional<Proxy> parseProxyServer(String proxyServer) {
        String raw = proxyServer == null ? "" : proxyServer.trim();
        if (raw.isBlank()) {
            return Optional.empty();
        }
        Proxy.Type type = Proxy.Type.HTTP;
        String endpoint = raw;
        if (raw.contains("=")) {
            Map<String, String> parts = new LinkedHashMap<>();
            for (String part : raw.split(";")) {
                int equals = part.indexOf('=');
                if (equals > 0 && equals < part.length() - 1) {
                    parts.put(part.substring(0, equals).trim().toLowerCase(), part.substring(equals + 1).trim());
                }
            }
            endpoint = firstNonBlank(parts.get("https"), parts.get("http"), parts.get("socks"));
            if (endpoint != null && endpoint.equals(parts.get("socks"))) {
                type = Proxy.Type.SOCKS;
            }
        }
        return proxyFromEndpoint(endpoint, type);
    }

    private Optional<Proxy> proxyFromEndpoint(String endpoint, Proxy.Type type) {
        if (endpoint == null || endpoint.isBlank()) {
            return Optional.empty();
        }
        String value = endpoint.trim();
        if (value.startsWith("http://")) {
            value = value.substring("http://".length());
            type = Proxy.Type.HTTP;
        } else if (value.startsWith("https://")) {
            value = value.substring("https://".length());
            type = Proxy.Type.HTTP;
        } else if (value.startsWith("socks://")) {
            value = value.substring("socks://".length());
            type = Proxy.Type.SOCKS;
        }
        int slash = value.indexOf('/');
        if (slash >= 0) {
            value = value.substring(0, slash);
        }
        int colon = value.lastIndexOf(':');
        if (colon <= 0 || colon >= value.length() - 1) {
            return Optional.empty();
        }
        String host = value.substring(0, colon).trim();
        try {
            int port = Integer.parseInt(value.substring(colon + 1).trim());
            if (host.isBlank() || port <= 0 || port > 65535) {
                return Optional.empty();
            }
            return Optional.of(new Proxy(type, InetSocketAddress.createUnresolved(host, port)));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private String readWindowsInternetSetting(String name) {
        try {
            Process process = new ProcessBuilder("reg", "query",
                    "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings",
                    "/v", name)
                    .redirectErrorStream(true)
                    .start();
            byte[] output = process.getInputStream().readAllBytes();
            if (!process.waitFor(3, TimeUnit.SECONDS) || process.exitValue() != 0) {
                return null;
            }
            String text = new String(output, Charset.defaultCharset());
            for (String line : text.split("\\R")) {
                String trimmed = line.trim();
                if (trimmed.startsWith(name)) {
                    String[] columns = trimmed.split("\\s+", 3);
                    if (columns.length == 3) {
                        return columns[2].trim();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private boolean isProxy(Proxy proxy) {
        return proxy != null && proxy != Proxy.NO_PROXY && proxy.type() != Proxy.Type.DIRECT;
    }

    private String describeProxy(Proxy proxy) {
        if (!isProxy(proxy)) {
            return "DIRECT";
        }
        SocketAddress address = proxy.address();
        if (address instanceof InetSocketAddress inet) {
            return proxy.type() + " " + inet.getHostString() + ":" + inet.getPort();
        }
        return proxy.type().toString();
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
