package com.example.chatseedance2.repository;

import com.example.chatseedance2.entity.VideoTaskEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface VideoTaskEventRepository extends JpaRepository<VideoTaskEvent, Long> {

    List<VideoTaskEvent> findTop100ByTaskIdOrderByCreatedAtDesc(Long taskId);
}
