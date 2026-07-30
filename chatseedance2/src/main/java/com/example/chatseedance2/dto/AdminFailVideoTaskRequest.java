package com.example.chatseedance2.dto;

import jakarta.validation.constraints.Size;

public record AdminFailVideoTaskRequest(
        @Size(max = 1000, message = "失败原因过长") String reason
) {
}
