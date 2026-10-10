package com.pcupgradelab.catalog.price;

import com.pcupgradelab.catalog.CatalogSourceName;
import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;

/** 검토한 정확한 판매 구성의 국내 신품 상품가만 입력하는 파일 계약. */
public record CatalogPriceImportBatch(Integer schemaVersion, List<Item> items) {
    public CatalogPriceImportBatch {
        if (schemaVersion == null || schemaVersion != 1 || items == null || items.isEmpty()
                || items.size() > 1000 || items.stream().anyMatch(item -> item == null)) {
            throw new IllegalArgumentException("Price import requires schemaVersion=1 and 1..1000 items");
        }
        items = List.copyOf(items);
    }

    public record Item(Product product, Offer offer, Price price) {
        public Item {
            if (product == null || offer == null || price == null) {
                throw new IllegalArgumentException("Each price item requires product, offer and price");
            }
        }
    }

    public record Product(CatalogSourceName sourceName, String externalId,
                          String manufacturer, String modelName, String partNumber) {
        public Product {
            if (sourceName != CatalogSourceName.BUILDCORES && sourceName != CatalogSourceName.MANUFACTURER) {
                throw new IllegalArgumentException("Product identity must use BUILDCORES or MANUFACTURER sourceName");
            }
            externalId = text(externalId, "product.externalId", 128);
            manufacturer = text(manufacturer, "product.manufacturer", 100);
            modelName = text(modelName, "product.modelName", 255);
            if (partNumber != null) partNumber = text(partNumber, "product.partNumber", 128);
            if (sourceName == CatalogSourceName.MANUFACTURER && partNumber == null) {
                throw new IllegalArgumentException("MANUFACTURER price identity requires an exact product.partNumber");
            }
        }
    }

    public enum SaleUnit { PRODUCT, RAM_KIT }

    public record Offer(String sourceName, String externalId, String sourceUrl, String saleSku,
                        SaleUnit saleUnit, Integer moduleCount, Instant verifiedAt) {
        public Offer {
            sourceName = text(sourceName, "offer.sourceName", 32).toUpperCase(Locale.ROOT);
            if (!sourceName.matches("[A-Z0-9][A-Z0-9_-]*")) {
                throw new IllegalArgumentException("offer.sourceName must be a stable ASCII provider code");
            }
            externalId = text(externalId, "offer.externalId", 128);
            sourceUrl = url(sourceUrl, "offer.sourceUrl");
            saleSku = text(saleSku, "offer.saleSku", 255);
            if (saleUnit == null || (saleUnit == SaleUnit.PRODUCT && moduleCount != null)
                    || (saleUnit == SaleUnit.RAM_KIT && (moduleCount == null || moduleCount < 1))) {
                throw new IllegalArgumentException("PRODUCT requires no moduleCount; RAM_KIT requires a positive moduleCount");
            }
            verifiedAt = timestamp(verifiedAt, "offer.verifiedAt");
        }
    }

    public record Price(Long amountKrw, Instant observedAt, String evidenceUrl,
                        String condition, Boolean inStock) {
        public Price {
            if (amountKrw == null || amountKrw < 1 || amountKrw > 999999999999L) {
                throw new IllegalArgumentException("price.amountKrw must be an integer KRW amount in 1..999999999999");
            }
            observedAt = timestamp(observedAt, "price.observedAt");
            evidenceUrl = url(evidenceUrl, "price.evidenceUrl");
            if (!"NEW".equals(condition) || !Boolean.TRUE.equals(inStock)) {
                throw new IllegalArgumentException("Only available NEW products may be imported");
            }
        }
    }

    private static String text(String value, String field, int max) {
        String normalized = value == null ? null : value.strip();
        if (normalized == null || normalized.isEmpty() || normalized.length() > max) {
            throw new IllegalArgumentException(field + " is required (max " + max + " characters)");
        }
        return normalized;
    }

    private static String url(String value, String field) {
        String normalized = text(value, field, 2048);
        try {
            URI uri = URI.create(normalized);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null) {
                throw new IllegalArgumentException(field + " must be an absolute HTTPS URL without credentials");
            }
            return normalized;
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(field + " must be an absolute HTTPS URL without credentials", ex);
        }
    }

    private static Instant timestamp(Instant value, String field) {
        if (value == null || value.isAfter(Instant.now()) || value.isBefore(Instant.EPOCH)) {
            throw new IllegalArgumentException(field + " must be a past or present timestamp since 1970");
        }
        return value.truncatedTo(ChronoUnit.MICROS);
    }
}
