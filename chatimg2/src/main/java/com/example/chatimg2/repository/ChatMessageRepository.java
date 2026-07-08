package com.example.chatimg2.repository;

import com.example.chatimg2.entity.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Integer> {
    List<ChatMessage> findByConversationIdInOrderByCreatedAtAsc(Collection<Integer> conversationIds);
    void deleteByConversationId(Integer conversationId);
}
