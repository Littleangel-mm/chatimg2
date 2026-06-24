package com.example.chatimg2.service;

import com.example.chatimg2.config.ImageStorageConfig;
import com.example.chatimg2.dto.PageResult;
import com.example.chatimg2.entity.InspirationPrompt;
import com.example.chatimg2.repository.InspirationPromptRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class InspirationService {

    private final InspirationPromptRepository repository;
    private final ImageStorageConfig storageConfig;

    private static final int MAX_PAGE_SIZE = 60;

    public PageResult<InspirationPrompt> getPage(String mediaType, String category, int page, int size) {
        String type = "video".equals(mediaType) ? "video" : "image";
        int safeSize = size <= 0 ? 30 : Math.min(size, MAX_PAGE_SIZE);
        int safePage = Math.max(page, 0);

        Pageable pageable = PageRequest.of(safePage, safeSize,
                Sort.by(Sort.Direction.ASC, "sortOrder").and(Sort.by(Sort.Direction.ASC, "id")));

        Page<InspirationPrompt> result = (category != null && !category.isBlank())
                ? repository.findByMediaTypeAndCategory(type, category, pageable)
                : repository.findByMediaType(type, pageable);

        PageResult<InspirationPrompt> pageResult = new PageResult<>();
        pageResult.setList(result.getContent().stream().map(this::forResponse).toList());
        pageResult.setTotal(result.getTotalElements());
        pageResult.setPage(safePage);
        pageResult.setSize(safeSize);
        pageResult.setTotalPages(result.getTotalPages());
        return pageResult;
    }

    /** 本地文件缺失时回退到 sourceUrl，避免前端只显示提示词 */
    private InspirationPrompt forResponse(InspirationPrompt src) {
        String resolved = resolveDisplayUrl(src.getImageUrl(), src.getSourceUrl());
        if (Objects.equals(resolved, src.getImageUrl())) {
            return src;
        }
        InspirationPrompt out = new InspirationPrompt();
        out.setId(src.getId());
        out.setExternalId(src.getExternalId());
        out.setMediaType(src.getMediaType());
        out.setCategory(src.getCategory());
        out.setSubcategory(src.getSubcategory());
        out.setImageUrl(resolved);
        out.setSourceUrl(src.getSourceUrl());
        out.setPrompt(src.getPrompt());
        out.setSortOrder(src.getSortOrder());
        out.setCreatedAt(src.getCreatedAt());
        out.setUpdatedAt(src.getUpdatedAt());
        return out;
    }

    private String resolveDisplayUrl(String imageUrl, String sourceUrl) {
        if (imageUrl == null || imageUrl.isBlank()) {
            return sourceUrl != null && !sourceUrl.isBlank() ? sourceUrl : imageUrl;
        }
        if (imageUrl.startsWith("http://") || imageUrl.startsWith("https://")) {
            return imageUrl;
        }
        Path local = storageConfig.getBackupDir().resolve(imageUrl.replace('\\', '/'));
        if (Files.exists(local)) {
            return imageUrl;
        }
        if (sourceUrl != null && !sourceUrl.isBlank()) {
            return sourceUrl;
        }
        return imageUrl;
    }

    public List<String> getCategories(String mediaType) {
        String type = "video".equals(mediaType) ? "video" : "image";
        return repository.findCategories(type);
    }

    public Map<String, Object> stats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        long image = repository.countByMediaType("image");
        long video = repository.countByMediaType("video");
        stats.put("image", image);
        stats.put("video", video);
        stats.put("total", image + video);
        return stats;
    }

    /** 轻量元信息：供前端检测数据是否更新（version = 最新 updatedAt 的毫秒值） */
    public Map<String, Object> meta() {
        Map<String, Object> meta = stats();
        long version = repository.findMaxUpdatedAt()
                .map(t -> t.toInstant(ZoneOffset.UTC).toEpochMilli())
                .orElse(0L);
        meta.put("version", version);
        return meta;
    }
}
