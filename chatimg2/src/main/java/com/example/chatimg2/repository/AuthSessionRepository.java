package com.example.chatimg2.repository;

import com.example.chatimg2.entity.AuthSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface AuthSessionRepository extends JpaRepository<AuthSession, Integer> {
    Optional<AuthSession> findByTokenHash(String tokenHash);
    void deleteByTokenHash(String tokenHash);

    @Modifying
    @Query("delete from AuthSession session where session.userId = :userId")
    int deleteByUserId(@Param("userId") Integer userId);
}
