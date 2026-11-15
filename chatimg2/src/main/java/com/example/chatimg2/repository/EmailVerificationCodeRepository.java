package com.example.chatimg2.repository;

import com.example.chatimg2.entity.EmailVerificationCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface EmailVerificationCodeRepository extends JpaRepository<EmailVerificationCode, Integer> {
    Optional<EmailVerificationCode> findTopByEmailAndSceneAndUsedFalseOrderByCreatedAtDesc(String email, String scene);
}
