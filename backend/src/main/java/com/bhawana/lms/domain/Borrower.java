package com.bhawana.lms.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "borrower")
public class Borrower {

    @Id
    private UUID id;

    @Column(name = "full_name", nullable = false, length = 255)
    private String fullName;

    @Column(nullable = false, unique = true, length = 10)
    private String pan;

    @Column(nullable = false, length = 32)
    private String mobile;

    @Column(length = 255)
    private String email;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Borrower() {
    }

    public Borrower(String fullName, String pan, String mobile, String email) {
        this.id = UUID.randomUUID();
        this.fullName = fullName.trim();
        this.pan = pan.trim().toUpperCase();
        this.mobile = mobile.trim();
        this.email = normalizeEmail(email);
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getFullName() {
        return fullName;
    }

    public String getPan() {
        return pan;
    }

    public String getMobile() {
        return mobile;
    }

    public String getEmail() {
        return email;
    }

    public void refreshProfile(String fullName, String mobile, String email) {
        this.fullName = fullName.trim();
        this.mobile = mobile.trim();
        this.email = normalizeEmail(email);
    }

    private static String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        return email.trim().toLowerCase();
    }
}
