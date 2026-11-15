package com.example.chatimg2.repository;

import com.example.chatimg2.entity.ChatConversation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ChatConversationRepository extends JpaRepository<ChatConversation, Integer> {
    List<ChatConversation> findByUserIdAndDeletedAtIsNullOrderByUpdatedAtDesc(Integer userId);
    List<ChatConversation> findByUserIdOrderByUpdatedAtDesc(Integer userId);

    @Query("select conversation.clientId from ChatConversation conversation "
            + "where conversation.userId = :userId and conversation.deletedAt is not null "
            + "and conversation.clientId is not null")
    List<String> findDeletedClientIdsByUserId(@Param("userId") Integer userId);

    Optional<ChatConversation> findByUserIdAndClientId(Integer userId, String clientId);

    List<ChatConversation> findAllByUserIdAndClientId(Integer userId, String clientId);
}
