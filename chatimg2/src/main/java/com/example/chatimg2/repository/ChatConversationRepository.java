package com.example.chatimg2.repository;

import com.example.chatimg2.entity.ChatConversation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ChatConversationRepository extends JpaRepository<ChatConversation, Integer> {
    List<ChatConversation> findTop100ByUserIdOrderByUpdatedAtDesc(Integer userId);
    Optional<ChatConversation> findByUserIdAndClientId(Integer userId, String clientId);
}
