package com.pcupgradelab.catalog.price;

import com.pcupgradelab.catalog.identity.CatalogIdentityKind;
import com.pcupgradelab.catalog.identity.CatalogRole;
import com.pcupgradelab.pc.PartType;
import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Approved retail configuration for a distinct motherboard product without a published manufacturer PN. */
public record ReviewedMotherboardSaleConfiguration(
        String reviewId, PartType partType, CatalogIdentityKind identityKind, CatalogRole role,
        String manufacturerModelName, String domesticDistributor, String packageKind, Integer unitCount,
        String socketCode, String memoryType, String formFactor, Boolean wifiIncluded,
        String revisionScope, List<String> evidenceUrls, Instant verifiedAt) {

    public static final String SINGLE_BOARD = "DOMESTIC_RETAIL_SINGLE_BOARD";

    public ReviewedMotherboardSaleConfiguration {
        reviewId = text(reviewId, "reviewId", 128);
        if (!reviewId.matches("[A-Za-z0-9][A-Za-z0-9_.:-]*"))
            throw new IllegalArgumentException("reviewId must be a stable ASCII review identifier");
        if (partType != PartType.MOTHERBOARD || identityKind != CatalogIdentityKind.PHYSICAL_VARIANT
                || (role != CatalogRole.PURCHASE_CANDIDATE && role != CatalogRole.BOTH))
            throw new IllegalArgumentException("Reviewed sale configuration requires a distinct MOTHERBOARD PHYSICAL_VARIANT purchase product");
        manufacturerModelName = text(manufacturerModelName, "manufacturerModelName", 255);
        domesticDistributor = text(domesticDistributor, "domesticDistributor", 100);
        if (Set.of("UNKNOWN", "UNCONFIRMED", "NONE", "N/A", "NA", "미확인", "미정", "알수없음")
                .contains(domesticDistributor.toUpperCase(Locale.ROOT)))
            throw new IllegalArgumentException("domesticDistributor must identify the reviewed domestic distributor");
        if (!SINGLE_BOARD.equals(packageKind) || !Integer.valueOf(1).equals(unitCount))
            throw new IllegalArgumentException("Reviewed sale configuration must cover one domestic retail single board");
        if ((manufacturerModelName + " / " + domesticDistributor + " / " + packageKind).length() > 255)
            throw new IllegalArgumentException("Reviewed sale configuration must fit the exact saleSku field");
        socketCode = code(socketCode, "socketCode", 32);
        memoryType = code(memoryType, "memoryType", 10);
        formFactor = code(formFactor, "formFactor", 32);
        if (wifiIncluded == null)
            throw new IllegalArgumentException("Reviewed sale configuration must explicitly document WiFi inclusion");
        revisionScope = text(revisionScope, "revisionScope", 500);
        if (evidenceUrls == null || evidenceUrls.isEmpty() || evidenceUrls.size() > 16)
            throw new IllegalArgumentException("Reviewed sale configuration requires 1..16 evidence URLs");
        evidenceUrls = evidenceUrls.stream().map(ReviewedMotherboardSaleConfiguration::evidenceUrl).toList();
        if (new HashSet<>(evidenceUrls).size() != evidenceUrls.size())
            throw new IllegalArgumentException("Reviewed sale configuration evidence URLs must be unique");
        if (verifiedAt == null || verifiedAt.isBefore(Instant.EPOCH) || verifiedAt.isAfter(Instant.now()))
            throw new IllegalArgumentException("Reviewed sale configuration verifiedAt must be a past or present timestamp");
        verifiedAt = verifiedAt.truncatedTo(ChronoUnit.MICROS);
    }

    /** Sale mapping keeps the reviewed distributor and complete single-board package explicit. */
    public String saleSku() {
        return manufacturerModelName + " / " + domesticDistributor + " / " + packageKind;
    }

    private static String text(String value, String field, int limit) {
        if (value == null || value.isBlank() || value.strip().length() > limit)
            throw new IllegalArgumentException("reviewedSaleConfiguration." + field + " is required (max " + limit + " characters)");
        return value.strip();
    }

    private static String code(String value, String field, int limit) {
        String normalized = text(value, field, limit);
        if (!normalized.matches("[A-Z0-9][A-Z0-9_+.-]*"))
            throw new IllegalArgumentException("reviewedSaleConfiguration." + field + " must use an exact canonical code");
        return normalized;
    }

    private static String evidenceUrl(String value) {
        String normalized = text(value, "evidenceUrls entry", 2048);
        try {
            URI uri = URI.create(normalized);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getFragment() != null)
                throw new IllegalArgumentException();
            return normalized;
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Reviewed sale evidence must be an absolute HTTPS URL without credentials or fragment", ex);
        }
    }
}
