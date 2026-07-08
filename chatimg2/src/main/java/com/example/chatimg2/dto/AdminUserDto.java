package com.example.chatimg2.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class AdminUserDto {
    private Integer id;
    private String email;
    private String username;
    private String passwordMask;
    private String role;
    private String status;
    private Integer totalCredits;
    private Integer usedCredits;
    private Integer remainingCredits;
    private String createdAt;
    private String lastLoginAt;
    private List<String> boundKeys;
    private Integer boundKeyCount;
}
