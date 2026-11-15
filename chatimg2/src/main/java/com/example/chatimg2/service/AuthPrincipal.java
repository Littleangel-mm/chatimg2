package com.example.chatimg2.service;

import com.example.chatimg2.entity.Admin;
import com.example.chatimg2.entity.UserAccount;

public record AuthPrincipal(String role, UserAccount user, Admin admin) {
    public boolean isUser() {
        return user != null;
    }

    public boolean isAdmin() {
        return admin != null || "ADMIN".equals(role);
    }
}
