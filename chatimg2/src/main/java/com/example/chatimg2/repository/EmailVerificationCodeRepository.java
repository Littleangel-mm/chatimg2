package com.example.chatimg2.repository;

import com.example.chatimg2.entity.EmailVerificationCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.Optional;

public interface EmailVerificationCodeRepository extends JpaRepository<EmailVerificationCode, Integer> {
    Optional<EmailVerificationCode> findTopByEmailAndSceneAndUsedFalseOrderByCreatedAtDesc(String email, String scene);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select code from EmailVerificationCode code where code.id = "
            + "(select max(latest.id) from EmailVerificationCode latest "
            + "where latest.email = :email and latest.scene = :scene and latest.used = false)")
    Optional<EmailVerificationCode> findLatestUnusedForUpdate(
            @Param("email") String email, @Param("scene") String scene);
}
