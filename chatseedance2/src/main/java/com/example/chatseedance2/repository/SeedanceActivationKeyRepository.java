package com.example.chatseedance2.repository;

import com.example.chatseedance2.entity.ActivationKey;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SeedanceActivationKeyRepository extends JpaRepository<ActivationKey, Integer> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select key from SeedanceActivationKey key where key.id = :id")
    Optional<ActivationKey> findByIdForUpdate(@Param("id") Integer id);

    List<ActivationKey> findAllByUserIdOrderByCreatedAtDesc(Integer userId);

    List<ActivationKey> findAllByUserEmailOrderByCreatedAtDesc(String userEmail);
}
