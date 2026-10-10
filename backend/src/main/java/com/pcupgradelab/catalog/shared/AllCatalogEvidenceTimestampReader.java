package com.pcupgradelab.catalog.shared;

import com.pcupgradelab.catalog.CatalogProductSource;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.hibernate.Session;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Compares each immutable source using its original insertion contract, without altering stored values.
 * SeedService bound UTC wall-clock fields in the ordinary connection session. PilotImportService inserted
 * manufacturer evidence inside an explicit UTC session. These two histories must not share one JDBC time read.
 */
@Component
public class AllCatalogEvidenceTimestampReader {
    private final EntityManager entityManager;
    public AllCatalogEvidenceTimestampReader(EntityManager entityManager) { this.entityManager=entityManager; }

    public Instant read(CatalogProductSource source,boolean legacySeed) {
        if (source == null || source.getId() == null || !TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Evidence time verification requires an existing source and active transaction");
        return entityManager.unwrap(Session.class).doReturningWork(connection -> {
            if (!"MySQL".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName())) return source.getRetrievedAt();
            // No timezone SET, arithmetic offsets, tolerance or alternate interpretation is used here.
            String sql = legacySeed
                    ? "SELECT CAST(retrieved_at AS CHAR) FROM catalog_product_source WHERE id=? AND product_id=?"
                    : "SELECT UNIX_TIMESTAMP(retrieved_at) FROM catalog_product_source WHERE id=? AND product_id=?";
            try (var statement = connection.prepareStatement(sql)) {
                statement.setLong(1,source.getId());
                statement.setString(2,source.getProduct().getId());
                try (var result = statement.executeQuery()) {
                    if (!result.next()) throw new IllegalArgumentException("Reviewed source timestamp row is missing");
                    Instant timestamp = legacySeed ? legacyWallClock(result.getString(1)) : utcEpoch(result.getBigDecimal(1));
                    if (result.next()) throw new IllegalArgumentException("Reviewed source timestamp row is not unique");
                    return timestamp;
                }
            }
        });
    }

    /** Reconstructs precisely the UTC fields that the seed's Hibernate UTC binding sent to this session. */
    static Instant legacyWallClock(String timestamp) {
        if (timestamp == null) throw new IllegalArgumentException("Reviewed legacy source timestamp is missing");
        return LocalDateTime.parse(timestamp.replace(' ','T')).toInstant(ZoneOffset.UTC);
    }
    /** MySQL UNIX_TIMESTAMP(TIMESTAMP column) returns its internal UTC epoch, independent of session rendering. */
    static Instant utcEpoch(BigDecimal timestamp) {
        if (timestamp == null) throw new IllegalArgumentException("Reviewed UTC source timestamp is missing");
        var parts = timestamp.divideAndRemainder(BigDecimal.ONE);
        return Instant.ofEpochSecond(parts[0].longValueExact(),parts[1].movePointRight(9).longValueExact());
    }
}
