package com.pcupgradelab.catalog.shared;

import com.pcupgradelab.catalog.CatalogReferenceEstimate;
import java.net.URI;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Objects;
import java.util.UUID;
import static com.pcupgradelab.catalog.CatalogReferenceEstimate.*;

/** Typed evidence, scope and units are verified before any reference reaches the public catalog. */
final class SharedReferencePriceValidation {
    private SharedReferencePriceValidation() { }
    static boolean version(String value) { return value != null && value.matches("references-v1-[0-9a-f]{64}"); }
    static void validate(CatalogReferenceEstimate value, SharedPriceDtos.Identity identity,
                         SharedCatalogSnapshot catalog, Instant servedAt) {
        require(value != null && value.basis() != null && value.identityScope() != null
                && value.saleUnit() != null && value.confidence() != null && value.method() != null,
                "Reference price metadata is required");
        amount(value.amountKrw());
        require(value.saleUnit().name().equals(identity.saleUnit())
                && Objects.equals(value.moduleCount(), identity.moduleCount()), "Reference sale unit differs");
        require(value.reviewedAt() != null && !value.reviewedAt().isBefore(Instant.EPOCH)
                && !value.reviewedAt().isAfter(servedAt), "Reference review timestamp is invalid");
        date(value.sourceDate(), servedAt);
        require(text(value.notes(), 1000), "Reference explanation is required");
        boolean estimated = value.basis() == Basis.SIMILAR_PART_ESTIMATE;
        if (estimated) {
            require(value.identityScope() == IdentityScope.SIMILAR_SPEC
                    && value.confidence() == Confidence.ESTIMATED
                    && value.method() == Method.SPEC_NEIGHBOUR_MEDIAN,
                    "Similar reference must remain an estimate");
        } else {
            require(value.identityScope() == (value.basis() == Basis.HISTORICAL_RETAIL
                        ? IdentityScope.EXACT_PRODUCT : IdentityScope.MODEL)
                    && value.confidence() == Confidence.VERIFIED_MODEL
                    && value.method() == (value.basis() == Basis.LAUNCH_PRICE
                        ? Method.OFFICIAL_MODEL_LAUNCH : Method.DIRECT_MODEL_QUOTE),
                    "Reference basis and method differ");
            require(value.rangeLowKrw() == null && value.rangeHighKrw() == null,
                    "Verified reference must not carry an estimate range");
        }
        require((value.rangeLowKrw() == null) == (value.rangeHighKrw() == null), "Reference range is incomplete");
        if (value.rangeLowKrw() != null) {
            amount(value.rangeLowKrw()); amount(value.rangeHighKrw());
            require(value.rangeLowKrw() <= value.amountKrw() && value.amountKrw() <= value.rangeHighKrw(),
                    "Reference value is outside its range");
        }
        require(!estimated || value.rangeLowKrw() != null, "Estimate requires its source range");
        require(value.sourceQuotes() != null && !value.sourceQuotes().isEmpty()
                && value.sourceQuotes().size() <= 3, "Reference requires 1..3 source quotes");
        var quotes = new HashSet<SourceQuote>();
        for (var quote : value.sourceQuotes()) {
            require(quote != null && quotes.add(quote) && text(quote.modelName(), 255)
                    && text(quote.sourceName(), 100) && quote.saleUnit() != null,
                    "Reference source is invalid or duplicated");
            amount(quote.amountKrw()); date(quote.sourceDate(), servedAt); url(quote.sourceUrl());
            if (identity.type() == com.pcupgradelab.pc.PartType.RAM) {
                require(quote.saleUnit() == SaleUnit.PRODUCT ? Integer.valueOf(1).equals(quote.moduleCount())
                        : quote.moduleCount() != null && quote.moduleCount() >= 1 && quote.moduleCount() <= 64,
                        "RAM reference source unit is invalid");
            } else require(quote.saleUnit() == SaleUnit.PRODUCT && quote.moduleCount() == null,
                    "Non-RAM reference source must be one product");
            if (!estimated) require(quote.saleUnit().name().equals(identity.saleUnit())
                    && Objects.equals(quote.moduleCount(), identity.moduleCount()), "Direct reference sales unit differs");
            if (quote.canonicalId() != null) {
                require(UUID.fromString(quote.canonicalId()).toString().equals(quote.canonicalId())
                        && catalog.products().containsKey(quote.canonicalId()), "Reference source identity is unknown");
                var source = catalog.products().get(quote.canonicalId()).identity();
                require(source.type() == identity.type() && source.modelName().equals(quote.modelName())
                        && source.saleUnit().equals(quote.saleUnit().name())
                        && Objects.equals(source.moduleCount(), quote.moduleCount()),
                        "Reference source type or sales unit differs");
                require(!estimated || !quote.canonicalId().equals(identity.canonicalId()),
                        "Similar reference cannot quote itself");
            }
        }
    }
    private static void amount(long value) {
        require(value >= 1 && value <= 999999999999L, "Reference amount is invalid");
    }
    private static void date(java.time.LocalDate value, Instant servedAt) {
        require(value == null || (!value.isBefore(java.time.LocalDate.of(1970, 1, 1))
                && !value.isAfter(servedAt.atZone(ZoneOffset.UTC).toLocalDate())), "Reference source date is invalid");
    }
    private static void url(String value) {
        require(text(value, 2048), "Reference source URL is required");
        var uri = URI.create(value);
        require("https".equals(uri.getScheme()) && uri.getHost() != null && uri.getRawUserInfo() == null,
                "Reference source must use an absolute HTTPS URL without credentials");
    }
    private static boolean text(String value, int limit) {
        return value != null && !value.isBlank() && value.length() <= limit && value.equals(value.strip());
    }
    static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
