package com.pcupgradelab.catalog.powercase;

import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.hibernate.Session;
import org.springframework.stereotype.Component;

/** 모든 기존 테이블의 스키마·행을 같은 트랜잭션에서 해시로 대조한다. 원문 행은 출력하지 않는다. */
@Component
public class PowerCaseCatalogPreservationAudit {
    private static final Map<String, String> INSERT_TABLES = Map.of(
            "catalog_product", "id", "catalog_reference_price", "product_id", "catalog_product_source", "product_id",
            "psu_spec", "product_id", "psu_power_connector", "product_id", "case_spec", "product_id",
            "case_motherboard_form_factor", "product_id", "case_psu_form_factor", "product_id",
            "catalog_price_mapping", "product_id", "catalog_price_observation", "product_id");
    private final EntityManager entityManager;
    public PowerCaseCatalogPreservationAudit(EntityManager entityManager) { this.entityManager = entityManager; }
    public record TableDigest(long rows, String sha256) { }
    public Map<String, TableDigest> capture() { return capture(List.of()); }

    public void requireV15() {
        entityManager.unwrap(Session.class).doWork(connection -> {
            boolean mysql = "MySQL".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName());
            String quote = mysql ? "`" : "\"";
            String sql = "SELECT " + quote + "version" + quote + "," + quote + "success" + quote
                    + " FROM " + quote + "flyway_schema_history" + quote;
            boolean v15 = false;
            try (var statement = connection.prepareStatement(sql); var result = statement.executeQuery()) {
                while (result.next()) {
                    if (!result.getBoolean(2)) throw new SQLException("Failed Flyway history requires separate review");
                    if ("15".equals(result.getString(1))) v15 = true;
                }
            }
            if (!v15) throw new SQLException("V15 must already be applied; this importer never runs Flyway");
        });
    }

    public Map<String, TableDigest> capture(Collection<String> excludedIds) {
        var ids = List.copyOf(excludedIds);
        return entityManager.unwrap(Session.class).doReturningWork(connection -> {
            boolean mysql = "MySQL".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName());
            String schema = mysql ? connection.getCatalog() : connection.getSchema();
            var tables = new TreeMap<String, String>();
            try (var statement = connection.prepareStatement("SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA=? AND TABLE_TYPE='BASE TABLE'")) {
                statement.setString(1, schema);
                try (var result = statement.executeQuery()) {
                    while (result.next()) {
                        String actual = result.getString(1);
                        if (!actual.matches("[A-Za-z][A-Za-z0-9_]*")) throw new SQLException("Unsupported preservation table identifier");
                        if (tables.put(actual.toLowerCase(Locale.ROOT), actual) != null) throw new SQLException("Ambiguous preservation table identifier");
                    }
                }
            }
            if (!tables.keySet().containsAll(INSERT_TABLES.keySet())) throw new SQLException("Complete V15 preservation schema is required");
            var resultDigests = new TreeMap<String, TableDigest>();
            String quote = mysql ? "`" : "\"";
            for (var table : tables.entrySet()) {
                String owner = ids.isEmpty() ? null : INSERT_TABLES.get(table.getKey());
                String sql = "SELECT * FROM " + quote + table.getValue() + quote;
                if (owner != null) sql += " WHERE " + quote + (mysql ? owner : owner.toUpperCase(Locale.ROOT)) + quote
                        + " NOT IN (" + String.join(",", Collections.nCopies(ids.size(), "?")) + ")";
                try (var statement = connection.prepareStatement(sql)) {
                    if (owner != null) for (int i = 0; i < ids.size(); i++) statement.setString(i + 1, ids.get(i));
                    try (var rows = statement.executeQuery()) {
                        var metadata = rows.getMetaData();
                        var schemaHash = digest();
                        for (int i = 1; i <= metadata.getColumnCount(); i++) {
                            field(schemaHash, metadata.getColumnName(i).toLowerCase(Locale.ROOT));
                            field(schemaHash, metadata.getColumnTypeName(i));
                        }
                        var rowHashes = new ArrayList<String>();
                        while (rows.next()) {
                            var rowHash = digest();
                            for (int i = 1; i <= metadata.getColumnCount(); i++) {
                                int type = metadata.getColumnType(i);
                                if (type == Types.BINARY || type == Types.VARBINARY || type == Types.LONGVARBINARY || type == Types.BLOB) {
                                    byte[] bytes = rows.getBytes(i);
                                    field(rowHash, bytes == null ? null : HexFormat.of().formatHex(bytes));
                                } else field(rowHash, rows.getString(i));
                            }
                            rowHashes.add(HexFormat.of().formatHex(rowHash.digest()));
                        }
                        Collections.sort(rowHashes);
                        for (String rowHash : rowHashes) field(schemaHash, rowHash);
                        resultDigests.put(table.getKey(), new TableDigest(rowHashes.size(), HexFormat.of().formatHex(schemaHash.digest())));
                    }
                }
            }
            return Collections.unmodifiableMap(resultDigests);
        });
    }

    public void assertOnlyInsertions(Map<String, TableDigest> before, Map<String, TableDigest> after,
                                     Collection<String> newProductIds, Map<String, Integer> expectedRows) {
        if (!before.keySet().equals(after.keySet()) || !before.equals(capture(newProductIds)))
            throw new IllegalStateException("Existing database rows or schema changed; PSU/CASE transaction must roll back");
        for (var table : before.entrySet()) {
            if (after.get(table.getKey()).rows() != table.getValue().rows() + expectedRows.getOrDefault(table.getKey(), 0))
                throw new IllegalStateException("Unexpected insertion count in " + table.getKey() + "; transaction must roll back");
        }
    }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    private static void field(MessageDigest digest, String value) {
        if (value == null) { digest.update((byte) 0); return; }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) 1); digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes);
    }
}
