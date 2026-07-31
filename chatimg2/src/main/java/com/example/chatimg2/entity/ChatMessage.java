package com.example.chatimg2.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "chat_messages")
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "conversation_id", nullable = false)
    private Integer conversationId;

    @Column(name = "client_id", length = 120)
    private String clientId;

    @Column(nullable = false, length = 20)
    private String role;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(name = "image_path", length = 500)
    private String imagePath;

    @Column(name = "source_image_url", columnDefinition = "TEXT")
    private String sourceImageUrl;

    @Column(name = "generation_record_id")
    private Integer generationRecordId;

    @Column(name = "video_task_code", length = 40)
    private String videoTaskCode;

    @Column(length = 50)
    private String intent;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
