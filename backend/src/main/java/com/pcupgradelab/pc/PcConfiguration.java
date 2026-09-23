package com.pcupgradelab.pc;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "pc_configuration")
public class PcConfiguration {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "owner_key", nullable = false, length = 128)
    private String ownerKey;
    @Column(nullable = false, length = 100)
    private String name;
    @Version
    private long version;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @OneToMany(mappedBy = "pc", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<PcPart> parts = new ArrayList<>();

    protected PcConfiguration() { }

    public PcConfiguration(String ownerKey, String name, List<PartInput> parts) {
        if (ownerKey == null || ownerKey.isBlank() || ownerKey.length() > 128) {
            throw new IllegalArgumentException("ownerKey is required (max 128 characters)");
        }
        this.ownerKey = ownerKey;
        update(name, parts);
    }

    /** Call from a transactional service. A PUT replaces the whole part list. */
    public void update(String name, List<PartInput> replacement) {
        if (name == null || name.isBlank() || name.length() > 100) {
            throw new IllegalArgumentException("PC name is required (max 100 characters)");
        }
        if (replacement == null || replacement.isEmpty() || replacement.size() > 64) {
            throw new IllegalArgumentException("PC must contain 1 to 64 part entries");
        }
        // Validate every replacement before mutating the existing aggregate.
        var next = replacement.stream().map(input -> new PcPart(this, input)).toList();
        this.name = name.strip();
        this.parts.clear();
        this.parts.addAll(next);
        this.updatedAt = Instant.now();
    }

    @PrePersist
    void onCreate() { createdAt = updatedAt = Instant.now(); }

    public Long getId() { return id; }
    public String getOwnerKey() { return ownerKey; }
    public String getName() { return name; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public List<PcPart> getParts() { return List.copyOf(parts); }
}
