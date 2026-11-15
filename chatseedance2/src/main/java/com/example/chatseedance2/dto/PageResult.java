package com.example.chatseedance2.dto;

import java.util.List;

public record PageResult<T>(List<T> list, long total, int page, int size, int totalPages) {
}
