package com.example.chatimg2.controller;

import com.example.chatimg2.dto.ApiResponse;
import com.example.chatimg2.dto.PageResult;
import com.example.chatimg2.entity.InspirationPrompt;
import com.example.chatimg2.service.InspirationCrawlerService;
import com.example.chatimg2.service.InspirationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class InspirationController {

    private final InspirationService inspirationService;
    private final InspirationCrawlerService crawlerService;

    /** 用户端：灵感画廊分页列表 */
    @GetMapping("/inspiration")
    public ApiResponse<PageResult<InspirationPrompt>> list(
            @RequestParam(defaultValue = "image") String mediaType,
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "30") int size) {
        return ApiResponse.success(inspirationService.getPage(mediaType, category, page, size));
    }

    /** 用户端：分类列表 */
    @GetMapping("/inspiration/categories")
    public ApiResponse<List<String>> categories(@RequestParam(defaultValue = "image") String mediaType) {
        return ApiResponse.success(inspirationService.getCategories(mediaType));
    }

    /** 用户端：元信息（数据量 + 版本号，供前端检测是否需要刷新） */
    @GetMapping("/inspiration/meta")
    public ApiResponse<Map<String, Object>> meta() {
        return ApiResponse.success(inspirationService.meta());
    }

    /** 管理端：触发爬取（异步执行） */
    @PostMapping("/admin/inspiration/crawl")
    public ApiResponse<Map<String, Object>> crawl(@RequestParam(required = false) String mediaType) {
        boolean started = crawlerService.startAsync(mediaType);
        Map<String, Object> data = new HashMap<>();
        data.put("started", started);
        if (!started) {
            return ApiResponse.error(409, "已有爬取任务正在执行，请稍后");
        }
        return ApiResponse.success("爬取任务已开始，请稍后刷新查看数量", data);
    }

    /** 管理端：爬取状态与库存数量 */
    @GetMapping("/admin/inspiration/status")
    public ApiResponse<Map<String, Object>> status() {
        Map<String, Object> data = new HashMap<>();
        data.put("running", crawlerService.isRunning());
        data.put("lastFinishedAt", crawlerService.getLastFinishedAt());
        data.put("lastSummary", crawlerService.getLastSummary());
        data.put("stats", inspirationService.stats());
        return ApiResponse.success(data);
    }
}
