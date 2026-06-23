package com.example.chatimg2.service;

import com.example.chatimg2.dto.PageResult;
import com.example.chatimg2.entity.InspirationPrompt;
import com.example.chatimg2.repository.InspirationPromptRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class InspirationService {

    private final InspirationPromptRepository repository;

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
        pageResult.setList(result.getContent());
        pageResult.setTotal(result.getTotalElements());
        pageResult.setPage(safePage);
        pageResult.setSize(safeSize);
        pageResult.setTotalPages(result.getTotalPages());
        return pageResult;
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
}
