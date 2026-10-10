package com.pcupgradelab.catalog.shared;

import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.*;
import java.util.*;
import org.hibernate.Session;
import org.springframework.stereotype.Component;

/** Same-transaction, whole-database preservation proof. Only row counts and SHA-256 values leave this component. */
@Component
public class RetailCatalogPreservationAudit {
    public static final Map<String,String> INSERT_TABLES = Map.of(
            "catalog_product", "id", "catalog_product_source", "product_id", "motherboard_spec", "product_id",
            "catalog_reference_price", "product_id", "catalog_price_mapping", "product_id", "catalog_price_observation", "product_id");
    private final EntityManager entityManager;
    public RetailCatalogPreservationAudit(EntityManager entityManager) { this.entityManager = entityManager; }
    public record TableDigest(long rows, String sha256) { }
    public Map<String,TableDigest> capture() { return capture(null); }
    public Map<String,TableDigest> capture(String excludeInsertedProductId) {
        return entityManager.unwrap(Session.class).doReturningWork(connection -> {
            var tables = new TreeMap<String,String>();
            boolean mysql = "MySQL".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName());
            String schema = mysql ? connection.getCatalog() : connection.getSchema();
            try (var statement = connection.prepareStatement("SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA=? AND TABLE_TYPE='BASE TABLE'")) {
                statement.setString(1, schema);
                try (var result = statement.executeQuery()) {
                    while (result.next()) {
                        String actual = result.getString(1);
                        if (!actual.matches("[A-Za-z][A-Za-z0-9_]*")) throw new SQLException("Unsupported audit table identifier");
                        if (tables.put(actual.toLowerCase(Locale.ROOT), actual) != null) throw new SQLException("Ambiguous audit table identifier");
                    }
                }
            }
            if (tables.isEmpty() || !tables.keySet().containsAll(INSERT_TABLES.keySet()))
                throw new SQLException("Complete retail preservation schema is required");
            var digests = new TreeMap<String,TableDigest>();
            for (var table : tables.entrySet()) {
                String excludeColumn = excludeInsertedProductId == null ? null : INSERT_TABLES.get(table.getKey());
                String quote = mysql ? "`" : "\"";
                String column = excludeColumn == null ? null : mysql ? excludeColumn : excludeColumn.toUpperCase(Locale.ROOT);
                String sql = "SELECT * FROM " + quote + table.getValue() + quote
                        + (column == null ? "" : " WHERE " + quote + column + quote + "<>?");
                try (var statement = connection.prepareStatement(sql)) {
                    if (excludeColumn != null) statement.setString(1, excludeInsertedProductId);
                    try (var result = statement.executeQuery()) {
                        var metadata = result.getMetaData();
                        var rows = new ArrayList<String>();
                        var schemaDigest = digest();
                        for (int i = 1; i <= metadata.getColumnCount(); i++) {
                            field(schemaDigest, metadata.getColumnName(i).toLowerCase(Locale.ROOT));
                            field(schemaDigest, metadata.getColumnTypeName(i));
                        }
                        while (result.next()) {
                            var row = digest();
                            for (int i = 1; i <= metadata.getColumnCount(); i++) {
                                int type = metadata.getColumnType(i);
                                if (type == Types.BINARY || type == Types.VARBINARY || type == Types.LONGVARBINARY || type == Types.BLOB) {
                                    byte[] bytes = result.getBytes(i);
                                    field(row, bytes == null ? null : HexFormat.of().formatHex(bytes));
                                } else field(row, result.getString(i));
                            }
                            rows.add(HexFormat.of().formatHex(row.digest()));
                        }
                        Collections.sort(rows);
                        for (String row : rows) field(schemaDigest, row);
                        digests.put(table.getKey(), new TableDigest(rows.size(), HexFormat.of().formatHex(schemaDigest.digest())));
                    }
                }
            }
            return Collections.unmodifiableMap(digests);
        });
    }
    public void assertOnlyApprovedInsertions(Map<String,TableDigest> before, Map<String,TableDigest> after, String insertedProductId) {
        if (!before.keySet().equals(after.keySet()) || !before.equals(capture(insertedProductId)))
            throw new IllegalStateException("Existing database rows or schema changed; retail transaction must roll back");
        for (String table : before.keySet()) {
            long expected = before.get(table).rows() + (INSERT_TABLES.containsKey(table) ? 1 : 0);
            if (after.get(table).rows() != expected)
                throw new IllegalStateException("Unexpected row delta in " + table + "; retail transaction must roll back");
        }
    }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    private static void field(MessageDigest digest, String value) {
        if (value == null) { digest.update((byte)0); return; }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte)1);
        digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
        digest.update(bytes);
    }
}
