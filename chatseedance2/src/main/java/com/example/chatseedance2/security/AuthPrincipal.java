package com.example.chatseedance2.security;

import com.example.chatseedance2.entity.Admin;
import com.example.chatseedance2.entity.UserAccount;

public record AuthPrincipal(AppRole role, UserAccount user, Admin admin) {

    public boolean isUser() {
        return role == AppRole.USER && user != null;
    }

    public boolean isAdmin() {
        return role == AppRole.ADMIN && admin != null;
    }
}
