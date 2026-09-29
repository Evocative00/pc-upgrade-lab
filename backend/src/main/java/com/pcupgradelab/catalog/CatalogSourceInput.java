package com.pcupgradelab.catalog;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** 가져온 출처의 입력값. URL 형식과 원본 식별자를 검증하며 외부 페이지를 요청하지는 않는다. */
public record CatalogSourceInput(
        CatalogSourceName sourceName,
        String externalId,
        String sourceRevision,
        String sourceUrl,
        Map<String, Object> rawPayload,
        Instant retrievedAt
) {
    public CatalogSourceInput {
        if (sourceName == null) {
            throw new IllegalArgumentException("sourceName is required");
        }
        if (retrievedAt == null) {
            throw new IllegalArgumentException("retrievedAt is required");
        }
        externalId = optionalText(externalId, "externalId", 128);
        sourceRevision = optionalText(sourceRevision, "sourceRevision", 64);
        sourceUrl = validUrl(sourceUrl);
        rawPayload = CatalogJsonValues.immutableObject(rawPayload);
        // 실제 가져온 시각을 보존하되 DB의 TIMESTAMP(6) 정밀도에 맞춘다.
        retrievedAt = retrievedAt.truncatedTo(ChronoUnit.MICROS);

        if (sourceName == CatalogSourceName.BUILDCORES) {
            externalId = canonicalUuid(externalId, "externalId");
            if (sourceRevision == null || !sourceRevision.matches("(?:[0-9a-fA-F]{40}|[0-9a-fA-F]{64})")) {
                throw new IllegalArgumentException("BuildCores sourceRevision must be a full 40- or 64-digit hex commit");
            }
            sourceRevision = sourceRevision.toLowerCase(Locale.ROOT);
            if (rawPayload == null || !(rawPayload.get("opendb_id") instanceof String rawId)) {
                throw new IllegalArgumentException("BuildCores rawPayload must contain a string opendb_id");
            }
            if (!externalId.equals(canonicalUuid(rawId, "rawPayload.opendb_id"))) {
                throw new IllegalArgumentException("BuildCores rawPayload.opendb_id must match externalId");
            }
        }
    }

    private static String optionalText(String value, String field, int maxLength) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " must be at most " + maxLength + " characters");
        }
        return normalized;
    }

    private static String validUrl(String value) {
        String normalized = optionalText(value, "sourceUrl", 2048);
        if (normalized == null) {
            throw new IllegalArgumentException("sourceUrl is required");
        }
        try {
            URI uri = new URI(normalized);
            boolean supportedScheme = "https".equalsIgnoreCase(uri.getScheme())
                    || "http".equalsIgnoreCase(uri.getScheme());
            if (!uri.isAbsolute() || !supportedScheme || uri.getHost() == null
                    || uri.getHost().isBlank() || uri.getRawUserInfo() != null) {
                throw new IllegalArgumentException("sourceUrl must be an absolute HTTP(S) URL without user info");
            }
            return normalized;
        } catch (URISyntaxException ex) {
            throw new IllegalArgumentException("sourceUrl must be a valid HTTP(S) URL", ex);
        }
    }

    private static String canonicalUuid(String value, String field) {
        if (value == null || value.length() != 36) {
            throw new IllegalArgumentException("BuildCores " + field + " must be a canonical UUID");
        }
        try {
            String normalized = UUID.fromString(value).toString();
            if (!normalized.equalsIgnoreCase(value)) {
                throw new IllegalArgumentException("BuildCores " + field + " must be a canonical UUID");
            }
            return normalized;
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("BuildCores " + field + " must be a canonical UUID", ex);
        }
    }
}
