package com.example.chatimg2.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "chat_conversations")
public class ChatConversation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "key_id")
    private Integer keyId;

    @Column(name = "user_id")
    private Integer userId;

    @Column(name = "user_email", length = 120)
    private String userEmail;

    @Column(name = "client_id", length = 80)
    private String clientId;

    @Column(nullable = false, length = 255)
    private String title = "新对话";

    @Column(name = "conversation_type", nullable = false, length = 20)
    private String conversationType = "text2img";

    @Column(name = "creative_brief", columnDefinition = "TEXT")
    private String creativeBrief;

    @Column(name = "final_prompt", columnDefinition = "TEXT")
    private String finalPrompt;

    @Column(name = "reference_image_path", length = 500)
    private String referenceImagePath;

    @Column(name = "brief_version", nullable = false)
    private Integer briefVersion = 1;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

}
