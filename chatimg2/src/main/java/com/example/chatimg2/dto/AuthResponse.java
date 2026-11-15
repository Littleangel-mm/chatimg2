package com.example.chatimg2.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class AuthResponse {
    private String token;
    private Integer id;
    private String email;
    private String username;
    private String role;
    private Integer totalCredits;
    private Integer usedCredits;
    private Integer remainingCredits;
}
