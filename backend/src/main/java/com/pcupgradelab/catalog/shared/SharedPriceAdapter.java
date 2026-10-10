package com.pcupgradelab.catalog.shared;

import com.pcupgradelab.catalog.CatalogProductView;
import com.pcupgradelab.catalog.RamSpecRepository;
import com.pcupgradelab.pc.PartType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** 중앙 조회 결과만 승인된 공통 ID 범위에서 메모리에 보관한다. 조회 실패 시 로컬 DB 가격으로 대체하지 않는다. */
public final class SharedPriceAdapter {
    private final SharedCatalogSnapshot snapshot;
    private final SharedPriceClient client;
    private final Clock clock;
    private final SharedPriceFreshness freshness;
    private final RamSpecRepository ram;
    private final SharedReferencePriceAdapter references;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private record Cached(SharedPriceDtos.Item item, Instant receivedAt, Instant servedAt) { }

    private SharedPriceAdapter() {
        snapshot = null; client = null; clock = null; freshness = null; ram = null;
        references = SharedReferencePriceAdapter.disabled();
    }
    public static SharedPriceAdapter disabled() { return new SharedPriceAdapter(); }

    public SharedPriceAdapter(SharedCatalogSnapshot snapshot, SharedPriceClient client, Clock clock,
                              SharedPriceFreshness freshness, RamSpecRepository ram) {
        this(snapshot, client, clock, freshness, ram, SharedReferencePriceAdapter.disabled());
    }

    public SharedPriceAdapter(SharedCatalogSnapshot snapshot, SharedPriceClient client, Clock clock,
                              SharedPriceFreshness freshness, RamSpecRepository ram,
                              SharedReferencePriceAdapter references) {
        this.snapshot = Objects.requireNonNull(snapshot); this.client = Objects.requireNonNull(client);
        this.clock = Objects.requireNonNull(clock); this.freshness = Objects.requireNonNull(freshness);
        this.ram = Objects.requireNonNull(ram);
        this.references = Objects.requireNonNull(references);
    }

    public List<CatalogProductView> enrich(List<CatalogProductView> products) {
        if (snapshot == null || products.isEmpty()) return products;
        // 목록에서도 실제 RAM 구성은 한 번에 읽는다. 개별 제품 조회나 상세 제원 N+1은 만들지 않는다.
        var ramIds = products.stream().filter(p -> inScope(p) && p.type() == PartType.RAM)
                .map(CatalogProductView::id).toList();
        var counts = new LinkedHashMap<String, Integer>();
        for (var spec : ram.findAllById(ramIds)) counts.put(spec.getProductId(), spec.toSpecification().moduleCount());
        return enrich(products, counts);
    }

    /** 이미 상세 RAM 제원을 읽은 호출자도 동일 검증을 사용할 수 있다. */
    public List<CatalogProductView> enrich(List<CatalogProductView> products, Map<String, Integer> moduleCounts) {
        if (snapshot == null || products.isEmpty()) return products;
        var checkedAt = clock.instant();
        var requested = new LinkedHashSet<String>();
        for (var product : products) if (inScope(product) && identityMatches(product, moduleCounts))
            requested.add(product.canonicalId());
        var response = new LinkedHashMap<String, Cached>();
        var ids = List.copyOf(requested);
        for (int start = 0; start < ids.size(); start += client.batchSize()) {
            var batch = new LinkedHashSet<>(ids.subList(start, Math.min(ids.size(), start + client.batchSize())));
            try {
                var result = client.fetch(batch);
                var receivedAt = clock.instant();
                // Each validated batch is atomic; failure only affects this bounded request.
                for (var item : result.items()) {
                    var observed = new Cached(item, receivedAt, result.servedAt());
                    response.put(item.canonicalId(), observed);
                    cache.compute(item.canonicalId(), (id, previous) ->
                            previous != null && previous.receivedAt().isAfter(receivedAt)
                                    ? previous : observed);
                }
            } catch (RuntimeException ex) {
                // 실패·계약 불일치는 공개 응답의 UNAVAILABLE로 표시한다. 마지막 정상 관측만 보존한다.
            }
        }
        var byId = response;
        var enriched = products.stream().map(product -> {
            if (!inScope(product)) return product.withPrice(product.currentPrice(), new CatalogProductView.PriceStatus(
                    "LOCAL", "NOT_IN_SCOPE", null, null, null, null, product.currentPrice() != null));
            if (!identityMatches(product, moduleCounts)) return unavailable(product, null, checkedAt);
            var cached = cache.get(product.canonicalId());
            if (!byId.containsKey(product.canonicalId())) return unavailable(product, cached, checkedAt);
            var successful = byId.get(product.canonicalId());
            var item = successful.item();
            String state = classify(successful);
            boolean included = item.status() == SharedPriceDtos.Status.OK && !"EXPIRED".equals(state);
            return product.withPrice(item.price(), new CatalogProductView.PriceStatus("SHARED", item.status().name(),
                    state, snapshot.version(), checkedAt, successful.receivedAt(), included));
        }).toList();
        return references.enrich(enriched, moduleCounts);
    }

    private boolean inScope(CatalogProductView product) {
        return product.canonicalId() != null && snapshot.products().containsKey(product.canonicalId());
    }

    private boolean identityMatches(CatalogProductView product, Map<String, Integer> moduleCounts) {
        var expected = snapshot.products().get(product.canonicalId()).identity();
        return expected.canonicalId().equals(product.canonicalId()) && expected.type() == product.type()
                && expected.manufacturer().equals(product.manufacturer()) && expected.modelName().equals(product.modelName())
                && Objects.equals(expected.partNumber(), product.partNumber()) && expected.identityKind() == product.identityKind()
                && expected.role() == product.role() && expected.verificationStatus() == product.verificationStatus()
                && expected.active() == product.active()
                && (product.type() != PartType.RAM || (moduleCounts.containsKey(product.id())
                    && moduleCounts.get(product.id()) != null && moduleCounts.get(product.id()) > 0
                    && Objects.equals(expected.moduleCount(), moduleCounts.get(product.id()))));
    }

    private CatalogProductView unavailable(CatalogProductView product, Cached cached, Instant checkedAt) {
        var price = cached == null ? null : cached.item().price();
        String state = cached == null ? null : classify(cached);
        return product.withPrice(price, new CatalogProductView.PriceStatus("SHARED", "UNAVAILABLE", state,
                snapshot.version(), checkedAt, cached == null ? null : cached.receivedAt(), false));
    }

    private String classify(Cached observed) {
        // Advance the validated server clock by elapsed local time. A backward local clock step
        // never places the estimate before that validated timestamp.
        var elapsed = Duration.between(observed.receivedAt(), clock.instant());
        var estimatedServerTime = observed.servedAt().plus(elapsed.isNegative() ? Duration.ZERO : elapsed);
        var price = observed.item().price();
        return freshness.classify(price == null ? null : price.observedAt(), estimatedServerTime).name();
    }
}
