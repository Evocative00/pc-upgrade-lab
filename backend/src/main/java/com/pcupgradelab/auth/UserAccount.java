package com.pcupgradelab.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;

/** 로그인 회원의 내부 ID. 이메일은 계정 연결 키로 사용하지 않는다. */
@Entity
@Table(name = "users")
public class UserAccount {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 255)
    private String email;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected UserAccount() { }

    public UserAccount(String name, String email) {
        updateProfile(name, email);
    }

    public void updateProfile(String name, String email) {
        var trimmedName = name == null ? "" : name.strip();
        if (trimmedName.isEmpty() || trimmedName.length() > 100) {
            throw new IllegalArgumentException("name is required (max 100 characters)");
        }
        var trimmedEmail = email == null ? null : email.strip();
        if (trimmedEmail != null && trimmedEmail.length() > 255) {
            throw new IllegalArgumentException("email must be at most 255 characters");
        }
        this.name = trimmedName;
        this.email = trimmedEmail == null || trimmedEmail.isEmpty() ? null : trimmedEmail;
    }

    @PrePersist
    void onCreate() { createdAt = updatedAt = Instant.now(); }

    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getEmail() { return email; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
