package com.example.chatimg2.repository;

import com.example.chatimg2.entity.GenerationRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface GenerationRecordRepository extends JpaRepository<GenerationRecord, Integer> {
    List<GenerationRecord> findTop30ByKeyIdOrderByCreatedAtDesc(Integer keyId);
}
