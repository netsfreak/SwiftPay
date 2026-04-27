package com.example.user.model;

import java.util.UUID;

public record UserProfile(UUID userId, String fullName, String phoneNumber, String email) {
}
