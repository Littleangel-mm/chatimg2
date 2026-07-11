package com.example.chatimg2.repository;

import com.example.chatimg2.entity.GenerationRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import java.util.List;

public interface GenerationRecordRepository extends JpaRepository<GenerationRecord, Integer> {
    List<GenerationRecord> findTop30ByKeyIdOrderByCreatedAtDesc(Integer keyId);

    List<GenerationRecord> findTop30ByUserIdOrderByCreatedAtDesc(Integer userId);

    List<GenerationRecord> findTop100ByOrderByCreatedAtDesc();

    List<GenerationRecord> findTop50ByStatusAndImagePathIsNullAndCreatedAtBeforeOrderByCreatedAtAsc(
            String status, LocalDateTime createdAt);

    boolean existsByTaskCode(String taskCode);
}
