package com.example.chatseedance2.repository;

import com.example.chatseedance2.entity.VideoGenerationAsset;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface VideoGenerationAssetRepository extends JpaRepository<VideoGenerationAsset, Long> {

    List<VideoGenerationAsset> findByTaskIdOrderBySortOrderAsc(Long taskId);

    void deleteByTaskId(Long taskId);
}
