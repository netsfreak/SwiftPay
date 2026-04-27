package com.example.user.controller;

import com.example.user.model.UserProfile;
import com.example.user.service.UserProfileService;
import java.util.Collection;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {
    private final UserProfileService userProfileService;

    public UserController(UserProfileService userProfileService) {
        this.userProfileService = userProfileService;
    }

    @PostMapping
    public ResponseEntity<UserProfile> create(@RequestBody UserProfile request) {
        return ResponseEntity.ok(userProfileService.create(request));
    }

    @GetMapping("/{userId}")
    public ResponseEntity<UserProfile> get(@PathVariable UUID userId) {
        UserProfile profile = userProfileService.get(userId);
        return profile == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(profile);
    }

    @GetMapping
    public ResponseEntity<Collection<UserProfile>> list() {
        return ResponseEntity.ok(userProfileService.list());
    }
}
