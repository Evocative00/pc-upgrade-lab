package com.pcupgradelab.catalog.storage;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "motherboard_storage_source")
public class MotherboardStorageSource {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "profile_id", nullable = false) private MotherboardStorageProfile profile;
    @Column(name = "source_url", nullable = false, length = 2048) private String sourceUrl;
    @Column(name = "checked_at", nullable = false) private Instant checkedAt;
    @Column(name = "source_revision", length = 255) private String sourceRevision;
    @Column(name = "document_location", length = 255) private String documentLocation;
    @Column(name = "supported_facts", nullable = false, length = 4000) private String supportedFacts;

    protected MotherboardStorageSource() { }
    MotherboardStorageSource(MotherboardStorageProfile profile, MotherboardStorageSupport.Source value) {
        this.profile = profile; sourceUrl = value.sourceUrl(); checkedAt = value.checkedAt();
        sourceRevision = value.sourceRevision(); documentLocation = value.documentLocation();
        supportedFacts = value.supportedFacts();
    }
    MotherboardStorageSupport.Source toValue() {
        return new MotherboardStorageSupport.Source(sourceUrl, checkedAt, sourceRevision, documentLocation, supportedFacts);
    }
}
