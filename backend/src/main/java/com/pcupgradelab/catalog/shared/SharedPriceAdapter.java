package com.pcupgradelab.catalog.shared;

import com.pcupgradelab.catalog.CatalogProductView;
import com.pcupgradelab.catalog.RamSpecRepository;
import com.pcupgradelab.pc.PartType;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 중앙 14종의 읽기 결과만 메모리에 보관한다. 조회 실패 시 로컬 DB 가격으로 대체하지 않는다. */
public final class SharedPriceAdapter {
    private final SharedCatalogSnapshot snapshot;
    private final SharedPriceClient client;
    private final Clock clock;
    private final SharedPriceFreshness freshness;
    private final RamSpecRepository ram;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private record Cached(SharedPriceDtos.Item item, Instant receivedAt) { }

    private SharedPriceAdapter() { snapshot = null; client = null; clock = null; freshness = null; ram = null; }
    public static SharedPriceAdapter disabled() { return new SharedPriceAdapter(); }

    public SharedPriceAdapter(SharedCatalogSnapshot snapshot, SharedPriceClient client, Clock clock,
                              SharedPriceFreshness freshness, RamSpecRepository ram) {
        this.snapshot = Objects.requireNonNull(snapshot); this.client = Objects.requireNonNull(client);
        this.clock = Objects.requireNonNull(clock); this.freshness = Objects.requireNonNull(freshness);
        this.ram = Objects.requireNonNull(ram);
        if (snapshot.products().size() > 14) throw new IllegalArgumentException("Shared price scope exceeds fourteen products");
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
        Map<String, SharedPriceDtos.Item> response = Map.of();
        Instant successAt = null;
        boolean succeeded = false;
        if (!requested.isEmpty()) {
            try {
                response = client.fetch(requested).items().stream().collect(Collectors.toMap(
                        SharedPriceDtos.Item::canonicalId, Function.identity()));
                var receivedAt = clock.instant();
                // HTTP는 잠금 없이 병렬로 수행한다. 승인된 14개 키 안에서 최신 성공만 짧게 갱신한다.
                for (var item : response.values()) cache.compute(item.canonicalId(), (id, previous) ->
                        previous != null && previous.receivedAt().isAfter(receivedAt)
                                ? previous : new Cached(item, receivedAt));
                successAt = receivedAt;
                succeeded = true;
            } catch (RuntimeException ex) {
                // 실패·계약 불일치는 공개 응답의 UNAVAILABLE로 표시한다. 마지막 정상 관측만 보존한다.
            }
        }
        var byId = response;
        var receivedAt = successAt;
        boolean available = succeeded;
        return products.stream().map(product -> {
            if (!inScope(product)) return product.withPrice(product.currentPrice(), new CatalogProductView.PriceStatus(
                    "LOCAL", "NOT_IN_SCOPE", null, null, null, null, product.currentPrice() != null));
            if (!identityMatches(product, moduleCounts)) return unavailable(product, null, checkedAt);
            var cached = cache.get(product.canonicalId());
            if (!available || !byId.containsKey(product.canonicalId())) return unavailable(product, cached, checkedAt);
            var item = byId.get(product.canonicalId());
            String state = classify(item.price(), clock.instant());
            boolean included = item.status() == SharedPriceDtos.Status.OK && !"EXPIRED".equals(state);
            return product.withPrice(item.price(), new CatalogProductView.PriceStatus("SHARED", item.status().name(),
                    state, snapshot.version(), checkedAt, receivedAt, included));
        }).toList();
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
        String state = cached == null ? null : classify(price, clock.instant());
        return product.withPrice(price, new CatalogProductView.PriceStatus("SHARED", "UNAVAILABLE", state,
                snapshot.version(), checkedAt, cached == null ? null : cached.receivedAt(), false));
    }

    private String classify(CatalogProductView.CurrentPrice price, Instant now) {
        return freshness.classify(price == null ? null : price.observedAt(), now).name();
    }
}
