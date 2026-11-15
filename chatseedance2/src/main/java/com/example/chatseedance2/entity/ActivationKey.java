package com.example.chatseedance2.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity(name = "SeedanceActivationKey")
@Table(name = "activation_keys")
public class ActivationKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "key_code", nullable = false, unique = true, length = 64)
    private String keyCode;

    @Column(name = "total_credits", nullable = false)
    private Integer totalCredits;

    @Column(name = "used_credits", nullable = false)
    private Integer usedCredits;

    @Column(nullable = false)
    private Short status;

    @Column(name = "user_id")
    private Integer userId;

    @Column(name = "user_email", length = 120)
    private String userEmail;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
