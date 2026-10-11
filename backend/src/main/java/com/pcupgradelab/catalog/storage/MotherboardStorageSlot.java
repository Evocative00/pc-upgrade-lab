package com.pcupgradelab.catalog.storage;

import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "motherboard_storage_slot", uniqueConstraints =
        @UniqueConstraint(name = "uq_storage_slot_key", columnNames = {"profile_id", "slot_key"}))
public class MotherboardStorageSlot {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "profile_id", nullable = false) private MotherboardStorageProfile profile;
    @Column(name = "slot_key", nullable = false, length = 64) private String slotKey;
    @Column(name = "connector_type", nullable = false, length = 32) private String connectorType;
    @Column(name = "connector_key", length = 32) private String connectorKey;
    @ElementCollection
    @CollectionTable(name = "motherboard_storage_slot_length", joinColumns = @JoinColumn(name = "slot_id"))
    @Column(name = "length_code", nullable = false, length = 32) private List<String> supportedLengthCodes = new ArrayList<>();
    @ElementCollection
    @CollectionTable(name = "motherboard_storage_slot_bus", joinColumns = @JoinColumn(name = "slot_id"))
    @Column(name = "bus_interface", nullable = false, length = 32) private List<String> supportedBusInterfaces = new ArrayList<>();
    @ElementCollection
    @CollectionTable(name = "motherboard_storage_slot_protocol", joinColumns = @JoinColumn(name = "slot_id"))
    @Column(name = "interface_protocol", nullable = false, length = 32) private List<String> supportedProtocols = new ArrayList<>();
    @Column(name = "max_pcie_version", length = 16) private String maxPcieVersion;
    @Column(name = "max_pcie_lanes") private Integer maxPcieLanes;
    @Column(name = "sata_version", length = 16) private String sataVersion;
    @Column(name = "lane_source", length = 32) private String laneSource;
    @Column(name = "nvme_boot_support") private Boolean nvmeBootSupport;
    @Column(name = "notes", length = 2000) private String notes;
    @OneToMany(mappedBy = "slot", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("ruleKey ASC") private List<MotherboardStorageRule> rules = new ArrayList<>();

    protected MotherboardStorageSlot() { }
    MotherboardStorageSlot(MotherboardStorageProfile profile, MotherboardStorageSupport.Slot value) {
        this.profile = profile; slotKey = value.slotKey(); connectorType = value.connectorType();
        connectorKey = value.connectorKey(); supportedLengthCodes.addAll(value.supportedLengthCodes());
        supportedBusInterfaces.addAll(value.supportedBusInterfaces()); supportedProtocols.addAll(value.supportedProtocols());
        maxPcieVersion = value.maxPcieVersion(); maxPcieLanes = value.maxPcieLanes();
        sataVersion = value.sataVersion(); laneSource = value.laneSource(); nvmeBootSupport = value.nvmeBootSupport();
        notes = value.notes(); value.rules().forEach(rule -> rules.add(new MotherboardStorageRule(this, rule)));
    }
    MotherboardStorageSupport.Slot toValue() {
        return new MotherboardStorageSupport.Slot(slotKey, connectorType, connectorKey, supportedLengthCodes,
                supportedBusInterfaces, supportedProtocols, maxPcieVersion, maxPcieLanes, sataVersion,
                laneSource, nvmeBootSupport, notes, rules.stream().map(MotherboardStorageRule::toValue).toList());
    }
}
