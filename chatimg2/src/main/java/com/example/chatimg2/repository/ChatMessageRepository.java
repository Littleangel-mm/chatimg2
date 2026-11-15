package com.example.chatimg2.repository;

import com.example.chatimg2.entity.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Integer> {
    List<ChatMessage> findByConversationIdInOrderByCreatedAtAscIdAsc(Collection<Integer> conversationIds);
    List<ChatMessage> findByConversationId(Integer conversationId);
    Optional<ChatMessage> findByConversationIdAndClientId(Integer conversationId, String clientId);
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from ChatMessage m where m.conversationId = :conversationId")
    int deleteByConversationId(@Param("conversationId") Integer conversationId);
}
