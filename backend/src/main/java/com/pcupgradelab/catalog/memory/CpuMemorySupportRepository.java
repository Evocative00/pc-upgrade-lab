package com.pcupgradelab.catalog.memory;

import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 보완 자료를 별도 테이블에 저장한다. INSERT만 제공해 기존 자료를 조용히 덮어쓰지 않는다. */
@Repository
public class CpuMemorySupportRepository {
    private final JdbcTemplate jdbc;

    public CpuMemorySupportRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<Stored> findByProductId(String id) {
        var profiles = jdbc.query("SELECT * FROM cpu_memory_support WHERE product_id = ?", (rs, row) ->
                new Profile(rs.getBoolean("memory_types_known"), rs.getObject("max_memory_bytes", Long.class),
                        rs.getObject("channel_count", Integer.class), rs.getString("capacity_conditions"),
                        rs.getLong("evidence_source_id")), id);
        if (profiles.isEmpty()) return Optional.empty();
        var types = jdbc.query("""
                SELECT * FROM cpu_memory_type_support WHERE product_id = ? ORDER BY memory_type
                """, (rs, row) -> new CpuMemorySupport.TypeSupport(
                CpuMemorySupport.MemoryType.valueOf(rs.getString("memory_type")),
                rs.getObject("max_standard_data_rate_mts", Integer.class), rs.getString("data_rate_conditions")), id);
        var profile = profiles.getFirst();
        return Optional.of(new Stored(id, profile.evidenceSourceId(), new CpuMemorySupport(
                profile.memoryTypesKnown(), profile.maxMemoryBytes(), profile.channelCount(),
                profile.capacityConditions(), types)));
    }

    public void insert(String id, long evidenceSourceId, CpuMemorySupport support) {
        jdbc.update("""
                INSERT INTO cpu_memory_support (product_id, memory_types_known, max_memory_bytes,
                    channel_count, capacity_conditions, evidence_source_id) VALUES (?, ?, ?, ?, ?, ?)
                """, id, support.memoryTypesKnown(), support.maxMemoryBytes(), support.channelCount(),
                support.capacityConditions(), evidenceSourceId);
        for (var type : support.supportedTypes()) {
            jdbc.update("""
                    INSERT INTO cpu_memory_type_support (product_id, memory_type,
                        max_standard_data_rate_mts, data_rate_conditions) VALUES (?, ?, ?, ?)
                    """, id, type.memoryType().name(), type.maxStandardDataRateMts(), type.dataRateConditions());
        }
    }

    public record Stored(String productId, long evidenceSourceId, CpuMemorySupport support) { }
    private record Profile(boolean memoryTypesKnown, Long maxMemoryBytes, Integer channelCount,
                           String capacityConditions, long evidenceSourceId) { }
}
