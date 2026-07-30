package com.example.chatseedance2.service;

import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Service("videoTaskCodeService")
public class TaskCodeService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;

    public String generate() {
        String random = UUID.randomUUID().toString().replace("-", "")
                .substring(0, 8).toUpperCase();
        return "VIDEO-" + LocalDate.now().format(DATE) + "-" + random;
    }
}
