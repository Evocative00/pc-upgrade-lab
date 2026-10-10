package com.pcupgradelab.catalog.shared;

import com.pcupgradelab.catalog.CatalogReferenceEstimate;
import java.time.Instant;
import java.util.List;

/** Separate reference contract leaves existing current-price v1/v2 responses unchanged. */
public final class SharedReferencePriceDtos {
    private SharedReferencePriceDtos() { }
    public enum Status { OK, NO_REFERENCE, UNKNOWN_PRODUCT }
    public record Item(String canonicalId, SharedPriceDtos.Identity product,
                       CatalogReferenceEstimate referenceEstimate, Status status) { }
    public record Envelope(int schemaVersion, String catalogVersion, String referenceVersion,
                           Instant servedAt, List<Item> items) {
        public Envelope { if (items != null) items = List.copyOf(items); }
    }
    public record Product(SharedPriceDtos.Identity identity, CatalogReferenceEstimate referenceEstimate) { }
    public record Snapshot(int schemaVersion, String catalogVersion, String referenceVersion,
                           Instant generatedAt, boolean publicationApproved, List<Product> products) {
        public Snapshot { if (products != null) products = List.copyOf(products); }
    }
}
