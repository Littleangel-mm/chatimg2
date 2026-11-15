package com.example.chatimg2.dto;

import lombok.Data;

@Data
public class AuthRequest {
    private String account;
    private String email;
    private String password;
    private String code;
    private String scene;
}
