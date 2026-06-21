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

    @Column(name = "task_code", nullable = false, unique = true, length = 32)
    private String taskCode;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String prompt;

    /** 图片相对路径（仅存文件名，如 TASK-20260621-XXX.png），前端拼接 /img/ 展示 */
    @Column(name = "image_url", length = 500)
    private String imagePath;

    @Column(name = "local_path", length = 500)
    private String localPath;

    @Column(name = "generation_type", length = 20)
    private String generationType = "text2img";

    @Column(name = "source_image_path", length = 500)
    private String sourceImagePath;

    @Column(name = "credits_cost", nullable = false)
    private Integer creditsCost = 20;

    @Column(length = 20)
    private String status = "processing";

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
