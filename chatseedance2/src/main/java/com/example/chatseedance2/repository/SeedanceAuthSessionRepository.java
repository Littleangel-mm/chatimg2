package com.example.chatseedance2.repository;

import com.example.chatseedance2.entity.AuthSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SeedanceAuthSessionRepository extends JpaRepository<AuthSession, Integer> {
    Optional<AuthSession> findByTokenHash(String tokenHash);
}
