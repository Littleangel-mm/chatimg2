package com.example.chatimg2.repository;

import com.example.chatimg2.entity.GenerationRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface GenerationRecordRepository extends JpaRepository<GenerationRecord, Integer> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select record from GenerationRecord record where record.id = :id")
    Optional<GenerationRecord> findByIdForUpdate(@Param("id") Integer id);

    List<GenerationRecord> findTop30ByKeyIdOrderByCreatedAtDesc(Integer keyId);

    List<GenerationRecord> findTop30ByUserIdOrderByCreatedAtDesc(Integer userId);

    Optional<GenerationRecord> findFirstByUserIdAndClientMessageIdOrderByCreatedAtDesc(Integer userId, String clientMessageId);

    Optional<GenerationRecord> findFirstByKeyIdAndClientMessageIdOrderByCreatedAtDesc(Integer keyId, String clientMessageId);

    List<GenerationRecord> findByUserIdAndClientMessageIdInOrderByCreatedAtDesc(
            Integer userId, List<String> clientMessageIds);

    Optional<GenerationRecord> findByTaskCode(String taskCode);

    List<GenerationRecord> findTop100ByOrderByCreatedAtDesc();

    List<GenerationRecord> findTop50ByStatusAndImagePathIsNullAndCreatedAtBeforeOrderByCreatedAtAsc(
            String status, LocalDateTime createdAt);

    List<GenerationRecord> findTop50ByStatusAndImagePathIsNullAndErrorMessageContainingOrderByCreatedAtDesc(
            String status, String errorMarker);

    boolean existsByTaskCode(String taskCode);
}
