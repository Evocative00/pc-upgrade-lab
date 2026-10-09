package com.pcupgradelab.catalog.storage;

import jakarta.persistence.*;

@Entity
@Table(name = "motherboard_storage_rule", uniqueConstraints =
        @UniqueConstraint(name = "uq_storage_rule_key", columnNames = {"slot_id", "rule_key"}))
public class MotherboardStorageRule {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "slot_id", nullable = false) private MotherboardStorageSlot slot;
    @Column(name = "rule_key", nullable = false, length = 64) private String ruleKey;
    @Column(name = "cpu_condition", length = 1000) private String cpuCondition;
    @Column(name = "bios_condition", length = 1000) private String biosCondition;
    @Enumerated(EnumType.STRING)
    @Column(name = "effect", nullable = false, length = 32) private MotherboardStorageSupport.RuleEffect effect;
    @Column(name = "affected_slot_key", length = 64) private String affectedSlotKey;
    @Column(name = "raw_condition", nullable = false, length = 4000) private String rawCondition;

    protected MotherboardStorageRule() { }
    MotherboardStorageRule(MotherboardStorageSlot slot, MotherboardStorageSupport.Rule value) {
        this.slot = slot; ruleKey = value.ruleKey(); cpuCondition = value.cpuCondition();
        biosCondition = value.biosCondition(); effect = value.effect();
        affectedSlotKey = value.affectedSlotKey(); rawCondition = value.rawCondition();
    }
    MotherboardStorageSupport.Rule toValue() {
        return new MotherboardStorageSupport.Rule(ruleKey, cpuCondition, biosCondition, effect, affectedSlotKey, rawCondition);
    }
}
