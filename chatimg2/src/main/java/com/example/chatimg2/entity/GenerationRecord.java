package com.example.chatimg2.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "generation_records")
public class GenerationRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "key_id", nullable = false)
    private Integer keyId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String prompt;

    @Column(name = "image_url", columnDefinition = "TEXT")
    private String imageUrl;

    @Column(name = "local_path", length = 500)
    private String localPath;

    @Column(name = "generation_type", length = 20)
    private String generationType = "text2img";

    @Column(name = "source_image_path", length = 500)
    private String sourceImagePath;

    @Column(name = "credits_cost", nullable = false)
    private Integer creditsCost = 20;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
