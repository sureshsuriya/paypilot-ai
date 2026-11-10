package com.paypilot.service;

import com.paypilot.audit.AuditLogService;
import com.paypilot.dto.auth.AuthResponse;
import com.paypilot.dto.auth.LoginRequest;
import com.paypilot.dto.auth.RegisterRequest;
import com.paypilot.dto.auth.UserSummaryDto;
import com.paypilot.exception.DuplicateResourceException;
import com.paypilot.model.AuditActor;
import com.paypilot.model.User;
import com.paypilot.model.UserRole;
import com.paypilot.repository.UserRepository;
import com.paypilot.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtUtil jwtUtil;
    private final AuditLogService auditLogService;

    @Transactional
    public AuthResponse register(RegisterRequest request, String ipAddress) {
        String normalizedEmail = normalizeEmail(request.email());
        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new DuplicateResourceException("An account with this email already exists.");
        }

        User user = User.builder()
            .name(request.name().trim())
            .email(normalizedEmail)
            .passwordHash(passwordEncoder.encode(request.password()))
            .role(UserRole.USER)
            .build();

        User saved = userRepository.save(user);

        auditLogService.logEvent(
            saved.getId(),
            "USER",
            saved.getId(),
            "USER_REGISTERED",
            AuditActor.USER,
            Map.of("email", saved.getEmail(), "role", saved.getRole().name()),
            ipAddress
        );

        String token = jwtUtil.generateToken(saved);
        return AuthResponse.bearer(token, jwtUtil.getExpirationMs(), UserSummaryDto.from(saved));
    }

    @Transactional
    public AuthResponse login(LoginRequest request, String ipAddress) {
        String normalizedEmail = normalizeEmail(request.email());

        authenticationManager.authenticate(
            new UsernamePasswordAuthenticationToken(normalizedEmail, request.password())
        );

        User user = userRepository.findByEmail(normalizedEmail)
            .orElseThrow(() -> new BadCredentialsException("Invalid email or password."));

        auditLogService.logEvent(
            user.getId(),
            "USER",
            user.getId(),
            "USER_LOGIN",
            AuditActor.USER,
            Map.of("email", user.getEmail()),
            ipAddress
        );

        String token = jwtUtil.generateToken(user);
        return AuthResponse.bearer(token, jwtUtil.getExpirationMs(), UserSummaryDto.from(user));
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}

