package com.pcupgradelab.catalog.shared;

import com.pcupgradelab.catalog.CatalogProductView;
import com.pcupgradelab.catalog.CatalogVerificationStatus;
import com.pcupgradelab.catalog.identity.CatalogIdentityKind;
import com.pcupgradelab.catalog.identity.CatalogRole;
import com.pcupgradelab.pc.PartType;
import java.time.Instant;
import java.util.List;

/** Public read contract contains approved catalog facts only, never local IDs or PC data. */
public final class SharedPriceDtos {
    private SharedPriceDtos() { }
    public enum Status { OK, NO_PRICE, UNKNOWN_PRODUCT }
    public enum Freshness { FRESH, STALE, EXPIRED, NO_PRICE }
    public record Policy(long staleAfterSeconds, long expireAfterSeconds) { }
    public record Identity(String canonicalId, PartType type, String manufacturer, String modelName,
                           String partNumber, CatalogIdentityKind identityKind, CatalogRole role,
                           CatalogVerificationStatus verificationStatus, boolean active,
                           String saleUnit, Integer moduleCount) { }
    public record Item(String canonicalId, Identity product, CatalogProductView.CurrentPrice price,
                       Status status, Freshness freshness) { }
    public record Envelope(int schemaVersion, String catalogVersion, Instant servedAt, Policy policy,
                           List<Item> items) {
        public Envelope { items = List.copyOf(items); }
    }
    /** v2 freezes catalog identity independently of subsequently approved price observations. */
    public record EnvelopeV2(int schemaVersion, String catalogVersion, String priceVersion, Instant servedAt,
                             Policy policy, List<Item> items) {
        public EnvelopeV2 { items = List.copyOf(items); }
        public Envelope toEnvelope() { return new Envelope(schemaVersion, catalogVersion, servedAt, policy, items); }
    }
}
