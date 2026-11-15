package com.example.chatimg2.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 灵感画廊定时爬取：默认每天凌晨 00:00 自动爬取一次（图片 + 视频）。
 * 可通过 app.inspiration.schedule.enabled / .cron 配置。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InspirationScheduler {

    private final InspirationCrawlerService crawlerService;

    @Value("${app.inspiration.schedule.enabled:true}")
    private boolean scheduleEnabled;

    @Scheduled(cron = "${app.inspiration.schedule.cron:0 0 0 * * *}", zone = "${app.inspiration.schedule.zone:Asia/Shanghai}")
    public void autoCrawl() {
        if (!scheduleEnabled) {
            return;
        }
        log.info("Scheduled inspiration crawl triggered");
        boolean started = crawlerService.startAsync(null);
        if (!started) {
            log.warn("Scheduled inspiration crawl skipped: a crawl task is already running");
        }
    }
}
