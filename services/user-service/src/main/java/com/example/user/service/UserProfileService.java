package com.example.user.service;

import com.example.user.model.UserProfile;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

@Service
public class UserProfileService {
    private final Map<UUID, UserProfile> users = new ConcurrentHashMap<>();

    public UserProfile create(UserProfile request) {
        UUID userId = request.userId() == null ? UUID.randomUUID() : request.userId();
        UserProfile profile = new UserProfile(userId, request.fullName(), request.phoneNumber(), request.email());
        users.put(userId, profile);
        return profile;
    }

    public UserProfile get(UUID userId) {
        return users.get(userId);
    }

    public Collection<UserProfile> list() {
        return users.values();
    }
}
