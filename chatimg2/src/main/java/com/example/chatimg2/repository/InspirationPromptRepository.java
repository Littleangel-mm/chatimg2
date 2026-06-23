package com.example.chatimg2.repository;

import com.example.chatimg2.entity.InspirationPrompt;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface InspirationPromptRepository extends JpaRepository<InspirationPrompt, Integer> {

    boolean existsByExternalId(String externalId);

    Optional<InspirationPrompt> findByExternalId(String externalId);

    Page<InspirationPrompt> findByMediaType(String mediaType, Pageable pageable);

    Page<InspirationPrompt> findByMediaTypeAndCategory(String mediaType, String category, Pageable pageable);

    long countByMediaType(String mediaType);

    @Query("select distinct p.category from InspirationPrompt p "
            + "where p.mediaType = ?1 and p.category is not null and p.category <> '' order by p.category")
    List<String> findCategories(String mediaType);

    @Query("select max(p.updatedAt) from InspirationPrompt p")
    Optional<LocalDateTime> findMaxUpdatedAt();
}
