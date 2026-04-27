package com.example.auth.model;

public class AuthDtos {
    public record LoginRequest(String phoneNumber, String password) {}
    public record RegisterRequest(String phoneNumber, String password, String fullName) {}
    public record AuthResponse(String token, String phoneNumber, String role) {}
}
