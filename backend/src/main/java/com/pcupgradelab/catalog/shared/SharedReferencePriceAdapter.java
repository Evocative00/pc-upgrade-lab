package com.pcupgradelab.catalog.shared;

import com.pcupgradelab.catalog.CatalogProductView;
import com.pcupgradelab.catalog.CatalogReferenceEstimate;
import com.pcupgradelab.pc.PartType;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** References have no seven-day expiry. A failed independent request yields no reference, never a bundled substitute. */
public final class SharedReferencePriceAdapter {
    private final SharedCatalogSnapshot catalog;
    private final SharedReferencePriceClient client;
    private SharedReferencePriceAdapter() { catalog = null; client = null; }
    public static SharedReferencePriceAdapter disabled() { return new SharedReferencePriceAdapter(); }
    public SharedReferencePriceAdapter(SharedCatalogSnapshot catalog, SharedReferencePriceClient client) {
        this.catalog = Objects.requireNonNull(catalog); this.client = Objects.requireNonNull(client);
    }
    public List<CatalogProductView> enrich(List<CatalogProductView> products, Map<String, Integer> moduleCounts) {
        if (catalog == null || products.isEmpty()) return products;
        var requested = new LinkedHashSet<String>();
        for (var product : products) if (identityMatches(product, moduleCounts)) requested.add(product.canonicalId());
        var result = new LinkedHashMap<String, CatalogReferenceEstimate>();
        var ids = List.copyOf(requested);
        for (int start = 0; start < ids.size(); start += client.batchSize()) {
            var batch = new LinkedHashSet<>(ids.subList(start, Math.min(ids.size(), start + client.batchSize())));
            try {
                var response = client.fetch(batch);
                for (var item : response.items()) if (item.status() == SharedReferencePriceDtos.Status.OK)
                    result.put(item.canonicalId(), item.referenceEstimate());
            } catch (RuntimeException ex) {
                // Avoid repeating an unavailable provider across the remaining batches in this page.
                break;
            }
        }
        return products.stream().map(product -> product.withReferenceEstimate(
                identityMatches(product, moduleCounts) ? result.get(product.canonicalId()) : null)).toList();
    }
    private boolean identityMatches(CatalogProductView product, Map<String, Integer> moduleCounts) {
        if (product.canonicalId() == null) return false;
        var entry = catalog.products().get(product.canonicalId());
        if (entry == null || entry.price() != null) return false;
        var expected = entry.identity();
        return expected.type() == product.type() && expected.manufacturer().equals(product.manufacturer())
                && expected.modelName().equals(product.modelName()) && Objects.equals(expected.partNumber(), product.partNumber())
                && expected.identityKind() == product.identityKind() && expected.role() == product.role()
                && expected.verificationStatus() == product.verificationStatus() && expected.active() == product.active()
                && (product.type() != PartType.RAM || Objects.equals(expected.moduleCount(), moduleCounts.get(product.id())));
    }
}
