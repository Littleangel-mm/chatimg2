package com.example.chatseedance2.security;

import com.example.chatseedance2.exception.ForbiddenException;
import com.example.chatseedance2.exception.UnauthorizedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Arrays;

@Component
@RequiredArgsConstructor
public class RbacInterceptor implements HandlerInterceptor {

    private final AuthService authService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method) || "OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        RequireRole requirement = AnnotatedElementUtils.findMergedAnnotation(method.getMethod(), RequireRole.class);
        if (requirement == null) {
            requirement = AnnotatedElementUtils.findMergedAnnotation(method.getBeanType(), RequireRole.class);
        }
        if (requirement == null) {
            return true;
        }
        AuthPrincipal principal = authService.current(request)
                .orElseThrow(() -> new UnauthorizedException("请先登录"));
        if (Arrays.stream(requirement.value()).noneMatch(role -> role == principal.role())) {
            throw new ForbiddenException("无权访问此功能");
        }
        return true;
    }
}
