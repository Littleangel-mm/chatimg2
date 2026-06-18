package com.example.chatimg2.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "activation_keys")
public class ActivationKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "key_code", nullable = false, unique = true, length = 64)
    private String keyCode;

    @Column(name = "total_credits", nullable = false)
    private Integer totalCredits = 100;

    @Column(name = "used_credits", nullable = false)
    private Integer usedCredits = 0;

    @Column(nullable = false)
    private Short status = 1;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public int getRemainingCredits() {
        return totalCredits - usedCredits;
    }
}
