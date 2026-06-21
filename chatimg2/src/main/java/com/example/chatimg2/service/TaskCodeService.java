package com.example.chatimg2.service;

import com.example.chatimg2.repository.GenerationRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TaskCodeService {

    private final GenerationRecordRepository recordRepository;

    public String generate() {
        String date = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String code;
        do {
            String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
            code = "TASK-" + date + "-" + suffix;
        } while (recordRepository.existsByTaskCode(code));
        return code;
    }
}
