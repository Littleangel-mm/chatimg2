package com.example.chatimg2.service;

import com.example.chatimg2.dto.AuthResponse;
import com.example.chatimg2.entity.UserAccount;
import com.example.chatimg2.exception.BusinessException;
import com.example.chatimg2.repository.UserAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RegistrationService {

    private final EmailCodeService emailCodeService;
    private final UserAccountRepository userRepository;
    private final PasswordService passwordService;
    private final AuthService authService;

    @Transactional
    public AuthResponse register(String email, String password, String code) {
        if (userRepository.existsByEmail(email)) {
            throw new BusinessException("EMAIL_REGISTERED", "该邮箱已注册，请直接登录");
        }
        emailCodeService.verify(email, "REGISTER", code);

        UserAccount user = new UserAccount();
        user.setEmail(email);
        user.setUsername(email.substring(0, email.indexOf('@')));
        user.setPasswordHash(passwordService.encode(password));
        user = userRepository.saveAndFlush(user);
        return authService.createUserSession(user);
    }
}
