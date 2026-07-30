package com.example.chatseedance2.security;

import com.example.chatseedance2.entity.Admin;
import com.example.chatseedance2.entity.UserAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class RbacInterceptorTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;

    @BeforeEach
    void resetAuth() {
        reset(authService);
    }

    @Test
    void protectedEndpointWithoutSessionReturns401() throws Exception {
        when(authService.current(any())).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/video/generations"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void userCannotAccessAdminEndpoints() throws Exception {
        UserAccount user = new UserAccount();
        user.setId(1);
        when(authService.current(any())).thenReturn(Optional.of(
                new AuthPrincipal(AppRole.USER, user, null)));

        mockMvc.perform(get("/api/admin/video/generations"))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCannotUseUserGenerationEndpoints() throws Exception {
        Admin admin = new Admin();
        admin.setId(1);
        when(authService.current(any())).thenReturn(Optional.of(
                new AuthPrincipal(AppRole.ADMIN, null, admin)));

        mockMvc.perform(get("/api/video/generations"))
                .andExpect(status().isForbidden());
    }
}
