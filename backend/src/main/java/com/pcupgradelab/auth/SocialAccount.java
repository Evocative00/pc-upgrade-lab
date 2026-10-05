package com.pcupgradelab.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

/** 제공자가 부여한 고유 ID와 서비스 회원 ID의 연결. */
@Entity
@Table(name = "social_accounts")
public class SocialAccount {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 20)
    private String provider;

    @Column(name = "provider_user_id", nullable = false, length = 255)
    private String providerUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected SocialAccount() { }

    public SocialAccount(Long userId, String provider, String providerUserId) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("userId is required");
        }
        this.userId = userId;
        this.provider = required(provider, "provider", 20);
        this.providerUserId = required(providerUserId, "providerUserId", 255);
    }

    private static String required(String value, String field, int maxLength) {
        var trimmed = value == null ? "" : value.strip();
        if (trimmed.isEmpty() || trimmed.length() > maxLength) {
            throw new IllegalArgumentException(field + " is required (max " + maxLength + " characters)");
        }
        return trimmed;
    }

    @PrePersist
    void onCreate() { createdAt = Instant.now(); }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public String getProvider() { return provider; }
    public String getProviderUserId() { return providerUserId; }
    public Instant getCreatedAt() { return createdAt; }
}
