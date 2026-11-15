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
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

@Data
@Entity
@Table(name = "video_generation_tasks", indexes = {
        @Index(name = "idx_video_generation_tasks_user_created", columnList = "user_id, created_at"),
        @Index(name = "idx_video_generation_tasks_key_created", columnList = "key_id, created_at"),
        @Index(name = "idx_video_generation_tasks_provider_status", columnList = "provider_status, last_polled_at")
})
public class VideoGenerationTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_code", nullable = false, unique = true, length = 40)
    private String taskCode;

    @Column(nullable = false, length = 32)
    private String provider = "BAGEGE";

    @Column(name = "provider_task_id", unique = true, length = 160)
    private String providerTaskId;

    @Column(name = "client_message_id", length = 120)
    private String clientMessageId;

    @Column(name = "request_fingerprint", length = 64)
    private String requestFingerprint;

    @Column(name = "user_id")
    private Integer userId;

    @Column(name = "key_id")
    private Integer keyId;

    @Column(name = "user_email", length = 120)
    private String userEmail;

    @Column(nullable = false, length = 120)
    private String model;

    @Enumerated(EnumType.STRING)
    @Column(name = "generation_type", nullable = false, length = 32)
    private VideoGenerationType generationType;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String prompt;

    @Column(name = "duration_seconds", nullable = false)
    private Integer durationSeconds;

    @Column(name = "aspect_ratio", nullable = false, length = 20)
    private String aspectRatio;

    @Column(nullable = false, length = 20)
    private String resolution = "720p";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private VideoTaskStatus status = VideoTaskStatus.SUBMITTING;

    @Column(name = "provider_status", length = 32)
    private String providerStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "billing_status", nullable = false, length = 20)
    private VideoBillingStatus billingStatus = VideoBillingStatus.RESERVED;

    @Column(name = "quoted_unit_cost", precision = 12, scale = 4)
    private BigDecimal quotedUnitCost;

    @Column(name = "reserved_credits", nullable = false)
    private Integer reservedCredits = 0;

    @Column(name = "provider_cost", precision = 12, scale = 4)
    private BigDecimal providerCost;

    @Column(name = "charged_credits", nullable = false)
    private Integer chargedCredits = 0;

    @Column(name = "error_code", length = 120)
    private String errorCode;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "error_reason", columnDefinition = "TEXT")
    private String errorReason;

    @Column(name = "error_suggestion", columnDefinition = "TEXT")
    private String errorSuggestion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request_payload", columnDefinition = "jsonb")
    private Map<String, Object> requestPayload;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "provider_response", columnDefinition = "jsonb")
    private Map<String, Object> providerResponse;

    @Column(name = "next_poll_at")
    private LocalDateTime nextPollAt;

    @Column(name = "last_polled_at")
    private LocalDateTime lastPolledAt;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @Column(name = "submit_attempts", nullable = false)
    private Integer submitAttempts = 0;

    @Column(name = "poll_attempts", nullable = false)
    private Integer pollAttempts = 0;

    @Column(name = "last_state_changed_at", nullable = false)
    private LocalDateTime lastStateChangedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Version
    @Column(nullable = false)
    private Long version;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        if (lastStateChangedAt == null) {
            lastStateChangedAt = now;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
