package com.example.chatimg2.repository;

import com.example.chatimg2.entity.ActivationKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ActivationKeyRepository extends JpaRepository<ActivationKey, Integer> {
    Optional<ActivationKey> findByKeyCode(String keyCode);
    boolean existsByKeyCode(String keyCode);
    List<ActivationKey> findAllByUserIdOrderByCreatedAtDesc(Integer userId);
    List<ActivationKey> findAllByUserEmailOrderByCreatedAtDesc(String userEmail);
}
