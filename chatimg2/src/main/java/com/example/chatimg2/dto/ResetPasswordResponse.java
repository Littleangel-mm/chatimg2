package com.example.chatimg2.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ResetPasswordResponse {
    private Integer userId;
    private String email;
    private String tempPassword;
    private String passwordMask;
}
