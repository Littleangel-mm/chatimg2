package com.example.chatseedance2.repository;

import com.example.chatseedance2.entity.VideoGenerationTask;
import com.example.chatseedance2.entity.VideoTaskStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface VideoGenerationTaskRepository extends JpaRepository<VideoGenerationTask, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select task from VideoGenerationTask task where task.id = :id")
    Optional<VideoGenerationTask> findByIdForUpdate(@Param("id") Long id);

    Optional<VideoGenerationTask> findByTaskCode(String taskCode);

    @Query("select task.id from VideoGenerationTask task where task.taskCode = :taskCode")
    Optional<Long> findIdByTaskCode(@Param("taskCode") String taskCode);

    @Query("select task.userId from VideoGenerationTask task where task.id = :id")
    Optional<Integer> findUserIdById(@Param("id") Long id);

    Optional<VideoGenerationTask> findByProviderTaskId(String providerTaskId);

    Optional<VideoGenerationTask> findFirstByUserIdAndClientMessageIdOrderByCreatedAtDesc(
            Integer userId, String clientMessageId);

    Optional<VideoGenerationTask> findFirstByKeyIdAndClientMessageIdOrderByCreatedAtDesc(
            Integer keyId, String clientMessageId);

    Optional<VideoGenerationTask> findFirstByUserIdAndRequestFingerprintAndCreatedAtAfterOrderByCreatedAtDesc(
            Integer userId, String requestFingerprint, LocalDateTime createdAt);

    Optional<VideoGenerationTask> findFirstByKeyIdAndRequestFingerprintAndCreatedAtAfterOrderByCreatedAtDesc(
            Integer keyId, String requestFingerprint, LocalDateTime createdAt);

    List<VideoGenerationTask> findTop30ByUserIdOrderByCreatedAtDesc(Integer userId);

    List<VideoGenerationTask> findTop30ByKeyIdOrderByCreatedAtDesc(Integer keyId);

    @Query("""
            select task from VideoGenerationTask task
            where task.status in :statuses
              and (task.providerTaskId is not null or task.status = com.example.chatseedance2.entity.VideoTaskStatus.SUBMITTING)
              and (task.nextPollAt is null or task.nextPollAt <= :now)
            order by task.createdAt asc
            """)
    List<VideoGenerationTask> findReadyForRecovery(
            @Param("statuses") Collection<VideoTaskStatus> statuses,
            @Param("now") LocalDateTime now,
            Pageable pageable);
}
