package com.example.chatimg2.repository;

import com.example.chatimg2.entity.ActivationKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

public interface ActivationKeyRepository extends JpaRepository<ActivationKey, Integer> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select activationKey from ActivationKey activationKey where activationKey.id = :id")
    Optional<ActivationKey> findByIdForUpdate(@Param("id") Integer id);

    Optional<ActivationKey> findByKeyCode(String keyCode);
    boolean existsByKeyCode(String keyCode);
    List<ActivationKey> findAllByUserIdOrderByCreatedAtDesc(Integer userId);
    List<ActivationKey> findAllByUserEmailOrderByCreatedAtDesc(String userEmail);
}
