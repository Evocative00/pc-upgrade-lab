package com.pcupgradelab.catalog.seed;

import com.pcupgradelab.catalog.*;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 승인된 초기 자료를 한 번에 등록한다. 재실행은 같은 자료만 건너뛰며 기존 DB를 수정하지 않는다. */
@Service
public class CatalogSeedService {
    private final CatalogSeedLoader loader;
    private final CatalogEntryService entries;
    private final CatalogProductSourceRepository sources;
    private final EntityManager entityManager;

    public CatalogSeedService(CatalogSeedLoader loader, CatalogEntryService entries,
                              CatalogProductSourceRepository sources, EntityManager entityManager) {
        this.loader = loader;
        this.entries = entries;
        this.sources = sources;
        this.entityManager = entityManager;
    }

    @Transactional
    public Result seed() {
        // 기본 진입점에도 트랜잭션을 유지해 같은 클래스의 메서드를 호출해도 전체 저장을 묶는다.
        return seed(CatalogSeedBatch.INITIAL);
    }

    @Transactional
    public Result seed(CatalogSeedBatch batch) {
        if (batch == null) {
            throw new IllegalArgumentException("Catalog seed batch is required");
        }
        // 파일 전체를 먼저 검사한다. 이후의 INSERT도 모두 현재 트랜잭션에 참여한다.
        List<CatalogEntryCreateRequest> requests = loader.load(batch);
        List<Item> items = new ArrayList<>();
        for (var request : requests) {
            var original = request.sources().stream()
                    .filter(source -> source.sourceName() == CatalogSourceName.BUILDCORES)
                    .findFirst().orElseThrow();
            var existing = sources.findBySourceNameAndExternalId(original.sourceName(), original.externalId());
            CatalogEntryView entry;
            boolean created = existing.isEmpty();
            if (created) {
                entry = entries.create(request);
            } else {
                String productId = existing.orElseThrow().getProduct().getId();
                entry = entries.findById(productId).orElseThrow();
                assertMatches(request, entry);
            }
            items.add(new Item(entry.product().id(), entry.product().modelName(), created));
        }

        // JPA의 1차 캐시를 비운 뒤 DB에서 읽는다. 실패하면 이번 묶음에서 추가한 자료가 전부 롤백된다.
        entityManager.flush();
        entityManager.clear();
        for (int i = 0; i < items.size(); i++) {
            var item = items.get(i);
            var stored = entries.findById(item.productId()).orElseThrow();
            assertMatches(requests.get(i), stored);
            if (item.created()) assertInitialState(stored);
        }
        int created = (int) items.stream().filter(Item::created).count();
        return new Result(created, items.size() - created, items);
    }

    private static void assertMatches(CatalogEntryCreateRequest expected, CatalogEntryView actual) {
        var input = expected.product();
        var product = actual.product();
        boolean sameProduct = input.type() == product.type()
                && Objects.equals(input.manufacturer(), product.manufacturer())
                && Objects.equals(input.modelName(), product.modelName())
                && Objects.equals(input.partNumber(), product.partNumber());
        if (!sameProduct || !expected.specification().equals(actual.specification())) {
            throw conflict(input.modelName(), "product or specification differs");
        }
        for (var source : expected.sources()) {
            boolean found = actual.sources().stream().anyMatch(stored ->
                    source.sourceName() == stored.sourceName()
                    && Objects.equals(source.externalId(), stored.externalId())
                    && Objects.equals(source.sourceRevision(), stored.sourceRevision())
                    && source.sourceUrl().equals(stored.sourceUrl())
                    && sameJson(source.rawPayload(), stored.rawPayload()));
            if (!found) throw conflict(input.modelName(), "source revision, URL or payload differs");
        }
        // 기존 제품의 검증·활성·가격 상태와 추가 출처는 보존한다. 이 작업은 데이터 갱신 기능이 아니다.
    }

    private static IllegalStateException conflict(String modelName, String reason) {
        return new IllegalStateException("Catalog seed conflict for " + modelName + ": " + reason
                + ". Existing data was not overwritten; this seed transaction will roll back.");
    }

    private static void assertInitialState(CatalogEntryView stored) {
        var product = stored.product();
        if (product.active() || product.verificationStatus() != CatalogVerificationStatus.UNVERIFIED
                || product.referencePrice().status() != CatalogPriceStatus.UNCONFIRMED
                || product.referencePrice().amountKrw() != null) {
            throw new IllegalStateException("Unexpected initial catalog state: " + product.id());
        }
    }

    /** JSON 숫자는 JDBC/Jackson에 따라 Integer/Long/Double로 읽힐 수 있어 수치로 비교한다. */
    private static boolean sameJson(Object left, Object right) {
        if (left == right) return true;
        if (left == null || right == null) return false;
        if (left instanceof Number a && right instanceof Number b) {
            return new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString())) == 0;
        }
        if (left instanceof Map<?, ?> a && right instanceof Map<?, ?> b) {
            return a.keySet().equals(b.keySet())
                    && a.entrySet().stream().allMatch(entry -> sameJson(entry.getValue(), b.get(entry.getKey())));
        }
        if (left instanceof List<?> a && right instanceof List<?> b) {
            if (a.size() != b.size()) return false;
            for (int i = 0; i < a.size(); i++) if (!sameJson(a.get(i), b.get(i))) return false;
            return true;
        }
        return left.equals(right);
    }

    public record Item(String productId, String modelName, boolean created) { }

    public record Result(int created, int skipped, List<Item> items) {
        public Result { items = List.copyOf(items); }
    }
}
