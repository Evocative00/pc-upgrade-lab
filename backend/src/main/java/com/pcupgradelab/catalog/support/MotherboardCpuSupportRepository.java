package com.pcupgradelab.catalog.support;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MotherboardCpuSupportRepository {
    private final JdbcTemplate jdbc;
    public MotherboardCpuSupportRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Stored> findByMotherboardId(String id) {
        var profiles = jdbc.query("""
                SELECT * FROM motherboard_cpu_support_profile WHERE motherboard_product_id = ? ORDER BY revision_key
                """, (rs, row) -> new Profile(rs.getString("revision_key"),
                MotherboardCpuSupport.RevisionScope.valueOf(rs.getString("revision_scope")), rs.getString("hardware_revision"),
                rs.getString("conditions"), rs.getLong("evidence_source_id")), id);
        return profiles.stream().map(profile -> {
            var entries = jdbc.query("""
                    SELECT * FROM motherboard_cpu_support WHERE motherboard_product_id = ? AND revision_key = ?
                    ORDER BY cpu_product_id, variant_key
                    """, (rs, row) -> new MotherboardCpuSupport.Entry(rs.getString("cpu_product_id"), rs.getString("variant_key"),
                    MotherboardCpuSupport.SupportStatus.valueOf(rs.getString("support_status")), rs.getString("reported_cpu_name"),
                    rs.getString("cpu_stepping"), MotherboardCpuSupport.BiosRequirement.valueOf(rs.getString("bios_requirement")),
                    rs.getString("minimum_bios_version"), rs.getString("manufacturer_bios_label"), rs.getString("source_url"),
                    rs.getString("conditions")), id, profile.key());
            return new Stored(id, profile.evidenceSourceId(), new MotherboardCpuSupport(profile.scope(),
                    profile.revision(), profile.conditions(), entries));
        }).toList();
    }

    public Optional<Stored> findByRevision(String id, String revisionKey) {
        return findByMotherboardId(id).stream().filter(stored -> stored.support().revisionKey().equals(revisionKey)).findFirst();
    }

    public void insert(String id, long evidenceSourceId, MotherboardCpuSupport support) {
        jdbc.update("""
                INSERT INTO motherboard_cpu_support_profile (motherboard_product_id, revision_key, revision_scope,
                    hardware_revision, conditions, evidence_source_id) VALUES (?, ?, ?, ?, ?, ?)
                """, id, support.revisionKey(), support.revisionScope().name(), support.hardwareRevision(), support.conditions(), evidenceSourceId);
        for (var entry : support.entries()) {
            jdbc.update("""
                    INSERT INTO motherboard_cpu_support (motherboard_product_id, revision_key, cpu_product_id, variant_key,
                        support_status, reported_cpu_name, cpu_stepping, bios_requirement, minimum_bios_version,
                        manufacturer_bios_label, source_url, conditions) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, id, support.revisionKey(), entry.cpuProductId(), entry.variantKey(), entry.supportStatus().name(),
                    entry.reportedCpuName(), entry.cpuStepping(), entry.biosRequirement().name(), entry.minimumBiosVersion(),
                    entry.manufacturerBiosLabel(), entry.sourceUrl(), entry.conditions());
        }
    }

    public record Stored(String motherboardProductId, long evidenceSourceId, MotherboardCpuSupport support) { }
    private record Profile(String key, MotherboardCpuSupport.RevisionScope scope, String revision, String conditions, long evidenceSourceId) { }
}
