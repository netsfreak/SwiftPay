package com.example.auth.service;

import com.example.auth.model.AuthDtos.AuthResponse;
import com.example.auth.model.AuthDtos.LoginRequest;
import com.example.auth.model.AuthDtos.RegisterRequest;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
    private final Map<String, String> credentials = new ConcurrentHashMap<>();

    public AuthResponse register(RegisterRequest request) {
        credentials.put(request.phoneNumber(), request.password());
        return new AuthResponse(generateToken(), request.phoneNumber(), "USER");
    }

    public AuthResponse login(LoginRequest request) {
        String stored = credentials.get(request.phoneNumber());
        if (stored == null || !stored.equals(request.password())) {
            throw new IllegalArgumentException("Invalid credentials");
        }
        return new AuthResponse(generateToken(), request.phoneNumber(), "USER");
    }

    private String generateToken() {
        return "token-" + UUID.randomUUID();
    }
}
