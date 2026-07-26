package com.example.chatimg2.service;

import com.example.chatimg2.entity.Admin;
import com.example.chatimg2.repository.AdminRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AdminService {

    private final AdminRepository adminRepository;
    private final PasswordService passwordService;

    public Optional<Admin> login(String username, String password) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            return Optional.empty();
        }
        Optional<Admin> admin = adminRepository.findByUsername(username.trim());
        if (admin.isEmpty() || !passwordService.matchesAdmin(password, admin.get().getPassword())) {
            return Optional.empty();
        }
        if (passwordService.needsUpgrade(admin.get().getPassword())
                && passwordService.isBcryptLengthSupported(password)) {
            admin.get().setPassword(passwordService.encode(password));
            adminRepository.save(admin.get());
        }
        return admin;
    }
}
