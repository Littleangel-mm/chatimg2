package com.example.chatseedance2.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "video_generation_assets", indexes = {
        @Index(name = "idx_video_generation_assets_task_order", columnList = "task_id, direction, sort_order"),
        @Index(name = "idx_video_generation_assets_checksum", columnList = "checksum_sha256")
})
public class VideoGenerationAsset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private VideoAssetDirection direction;

    @Enumerated(EnumType.STRING)
    @Column(name = "media_type", nullable = false, length = 16)
    private VideoAssetMediaType mediaType;

    @Enumerated(EnumType.STRING)
    @Column(name = "asset_role", nullable = false, length = 20)
    private VideoAssetRole assetRole;

    @Column(name = "source_url", columnDefinition = "TEXT")
    private String sourceUrl;

    @Column(name = "local_path", length = 1000)
    private String localPath;

    @Column(name = "provider_url", columnDefinition = "TEXT")
    private String providerUrl;

    @Column(name = "provider_asset_id", length = 255)
    private String providerAssetId;

    @Column(name = "original_filename", length = 255)
    private String originalFilename;

    @Column(name = "mime_type", length = 120)
    private String mimeType;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "checksum_sha256", length = 64)
    private String checksumSha256;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
