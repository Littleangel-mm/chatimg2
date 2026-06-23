package com.example.chatimg2.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "inspiration_prompts")
public class InspirationPrompt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    /** 来源站点的唯一 id，用于去重/增量更新 */
    @Column(name = "external_id", unique = true, length = 64)
    private String externalId;

    @Column(name = "media_type", nullable = false, length = 16)
    private String mediaType = "image";

    @Column(length = 100)
    private String category;

    @Column(length = 100)
    private String subcategory;

    /** 前端展示用：本地相对路径(inspiration/xxx.webp) 或远程 URL */
    @Column(name = "image_url", length = 1000)
    private String imageUrl;

    /** 原始远程图片地址 */
    @Column(name = "source_url", length = 1000)
    private String sourceUrl;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String prompt;

    /** 保持来源站点顺序 */
    @Column(name = "sort_order")
    private Integer sortOrder = 0;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
