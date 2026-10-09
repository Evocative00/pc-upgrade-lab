package com.pcupgradelab.catalog.storage;

import com.pcupgradelab.catalog.CatalogProduct;
import com.pcupgradelab.pc.PartType;
import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "motherboard_storage_profile", uniqueConstraints =
        @UniqueConstraint(name = "uq_storage_profile_revision", columnNames = {"motherboard_product_id", "revision_key"}))
public class MotherboardStorageProfile {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "motherboard_product_id", nullable = false) private CatalogProduct motherboard;
    @Column(name = "revision_key", nullable = false, length = 64) private String revisionKey;
    @Enumerated(EnumType.STRING)
    @Column(name = "revision_scope", nullable = false, length = 16) private MotherboardStorageSupport.RevisionScope revisionScope;
    @Column(name = "hardware_revision", length = 48) private String hardwareRevision;
    @Column(name = "complete_data_known", nullable = false) private boolean completeDataKnown;
    @Column(name = "conditions", length = 2000) private String conditions;
    @OneToMany(mappedBy = "profile", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("slotKey ASC") private List<MotherboardStorageSlot> slots = new ArrayList<>();
    @OneToMany(mappedBy = "profile", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC") private List<MotherboardStorageSource> sources = new ArrayList<>();

    protected MotherboardStorageProfile() { }

    public MotherboardStorageProfile(CatalogProduct motherboard, MotherboardStorageSupport value) {
        if (motherboard == null || motherboard.getType() != PartType.MOTHERBOARD || value == null) {
            throw new IllegalArgumentException("A MOTHERBOARD product and storage support are required");
        }
        this.motherboard = motherboard;
        revisionKey = value.revisionKey(); revisionScope = value.revisionScope();
        hardwareRevision = value.hardwareRevision(); completeDataKnown = value.completeDataKnown();
        conditions = value.conditions();
        value.slots().forEach(slot -> slots.add(new MotherboardStorageSlot(this, slot)));
        value.sources().forEach(source -> sources.add(new MotherboardStorageSource(this, source)));
    }

    public Long getId() { return id; }
    public MotherboardStorageSupport support() {
        return new MotherboardStorageSupport(revisionScope, hardwareRevision, completeDataKnown, conditions,
                slots.stream().map(MotherboardStorageSlot::toValue).toList(),
                sources.stream().map(MotherboardStorageSource::toValue).toList());
    }
}
