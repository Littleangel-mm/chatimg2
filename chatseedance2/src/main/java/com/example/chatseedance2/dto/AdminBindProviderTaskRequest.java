package com.example.chatseedance2.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AdminBindProviderTaskRequest(
        @NotBlank(message = "供应商任务 ID 不能为空")
        @Size(max = 160, message = "供应商任务 ID 过长") String providerTaskId
) {
}
