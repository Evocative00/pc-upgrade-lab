package com.pcupgradelab.catalog.shared;

import com.pcupgradelab.catalog.CatalogPriceStatus;
import com.pcupgradelab.catalog.CatalogProductView;
import com.pcupgradelab.catalog.CatalogReferenceEstimate;
import com.pcupgradelab.catalog.RamSpecRepository;
import com.pcupgradelab.pc.PartType;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static com.pcupgradelab.catalog.CatalogReferenceEstimate.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Real HTTP contract/adapter checks need neither Spring nor an H2/MySQL connection. */
class SharedReferencePriceTests {
    private static final Instant NOW = Instant.parse("2026-10-11T00:00:00Z");
    private static final String TOKEN = "a".repeat(64);
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private SharedCatalogSnapshot catalog;
    private SharedCatalogSnapshot.Entry unpriced;
    private SharedCatalogSnapshot.Entry priced;

    @BeforeEach void fixture() {
        catalog = SharedCatalogSnapshot.loadActive();
        unpriced = catalog.products().values().stream().filter(e -> e.price() == null
                && e.identity().type() == PartType.CPU).findFirst().orElseThrow();
        priced = catalog.products().values().stream().filter(e -> e.price() != null
                && e.identity().type() == PartType.CPU).findFirst().orElseThrow();
    }

    @Test void oldReferenceIsRetainedWithOriginalDateScopeAndSourceRatherThanInventingACurrentObservation() throws Exception {
        try (var server = new Server()) {
            var product = view(unpriced.identity());
            var result = adapter(server).enrich(List.of(product), Map.of()).getFirst();
            assertThat(result.referenceEstimate()).isEqualTo(estimate(unpriced.identity()));
            assertThat(result.referenceEstimate().sourceDate()).isEqualTo(LocalDate.of(2020, 7, 1));
            assertThat(result.referenceEstimate().reviewedAt()).isEqualTo(NOW.minus(Duration.ofDays(10)));
            assertThat(result.currentPrice()).isNull(); assertThat(result.priceStatus()).isNull();
            assertThat(result.referencePrice()).isEqualTo(product.referencePrice());
            assertThat(server.queries).containsExactly("canonicalIds=" + unpriced.identity().canonicalId());
            assertThat(server.headers).containsExactly(TOKEN + ":" + catalog.version());
            assertThat(server.queries.getFirst()).doesNotContain(product.id());
        }
    }

    @Test void failureNeverUsesBundledOrPreviouslySuccessfulReferenceAndNeverChangesCurrentValue() throws Exception {
        try (var server = new Server()) {
            var existing = view(unpriced.identity()).withPrice(priced.price(), null);
            var adapter = adapter(server);
            assertThat(adapter.enrich(List.of(existing), Map.of()).getFirst().referenceEstimate()).isNotNull();
            server.status = 503;
            var failed = adapter.enrich(List.of(existing), Map.of()).getFirst();
            assertThat(failed.referenceEstimate()).isNull(); assertThat(failed.currentPrice()).isEqualTo(priced.price());
            assertThat(failed.referencePrice()).isEqualTo(existing.referencePrice());
        }
    }

    @Test void enabledReferenceStillRunsAfterCurrentFailureAndReferenceFailurePreservesCurrentSuccess() throws Exception {
        try (var server = new Server()) {
            var policy = SharedPriceFreshness.defaults();
            var current = new SharedPriceClient(server.uri(), Duration.ofSeconds(1), 65536, catalog, clock, policy, TOKEN);
            var references = adapter(server);
            var combined = new SharedPriceAdapter(catalog, current, clock, policy, mock(RamSpecRepository.class), references);
            server.currentStatus = 503;
            var result = combined.enrich(List.of(view(unpriced.identity())), Map.of()).getFirst();
            assertThat(result.priceStatus().lookupStatus()).isEqualTo("UNAVAILABLE");
            assertThat(result.currentPrice()).isNull(); assertThat(result.referenceEstimate()).isNotNull();
            server.currentStatus = 200; server.status = 503;
            result = combined.enrich(List.of(view(priced.identity()), view(unpriced.identity())), Map.of()).getFirst();
            assertThat(result.currentPrice()).isEqualTo(priced.price());
            assertThat(result.priceStatus().lookupStatus()).isEqualTo("OK");
            assertThat(result.referenceEstimate()).isNull();
        }
    }

    @Test void approvedCurrentProductsAndMismatchedIdentitiesAreNotReferenceRequests() throws Exception {
        try (var server = new Server()) {
            var good = view(unpriced.identity());
            var bad = new CatalogProductView(good.id(), good.type(), good.manufacturer(), good.modelName(), "wrong-PN",
                    good.verificationStatus(), good.active(), good.createdAt(), good.updatedAt(), good.referencePrice(),
                    null, good.canonicalId(), good.modelId(), good.identityKind(), good.role());
            var unknown = new CatalogProductView(good.id(), good.type(), good.manufacturer(), good.modelName(), good.partNumber(),
                    good.verificationStatus(), good.active(), good.createdAt(), good.updatedAt(), good.referencePrice());
            var values = adapter(server).enrich(List.of(view(priced.identity()), bad, unknown), Map.of());
            assertThat(values).allSatisfy(v -> assertThat(v.referenceEstimate()).isNull());
            assertThat(server.queries).isEmpty();
        }
    }

    @Test void noReferenceAndUnknownAreValidEmptyResultsButCannotCarryValues() throws Exception {
        try (var server = new Server()) {
            server.transform = root -> {
                var item = (ObjectNode) root.withArray("items").get(0);
                item.put("status", "NO_REFERENCE"); item.putNull("referenceEstimate"); return root;
            };
            assertThat(client(server).fetch(Set.of(unpriced.identity().canonicalId())).items().getFirst().referenceEstimate()).isNull();
            server.transform = root -> {
                var item = (ObjectNode) root.withArray("items").get(0);
                item.put("status", "UNKNOWN_PRODUCT"); item.putNull("referenceEstimate"); item.putNull("product"); return root;
            };
            assertThat(client(server).fetch(Set.of(unpriced.identity().canonicalId())).items().getFirst().status())
                    .isEqualTo(SharedReferencePriceDtos.Status.UNKNOWN_PRODUCT);
            server.transform = root -> { ((ObjectNode) root.withArray("items").get(0)).put("status", "NO_REFERENCE"); return root; };
            assertThatThrownBy(() -> client(server).fetch(Set.of(unpriced.identity().canonicalId())))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void malformedScopeUnitDateSourceRangeOrContractFailsTheWholeBatch() throws Exception {
        var invalid = List.<UnaryOperator<ObjectNode>>of(
                root -> { root.put("catalogVersion", "other"); return root; },
                root -> { root.put("referenceVersion", "bad"); return root; },
                root -> { root.put("servedAt", NOW.plusSeconds(6).toString()); return root; },
                root -> { root.put("servedAt", NOW.minusSeconds(6).toString()); return root; },
                root -> { ref(root).put("amountKrw", 0); return root; },
                root -> { ref(root).put("amountKrw", 1.5); return root; },
                root -> { ref(root).put("amountKrw", "123456"); return root; },
                root -> { ref(root).put("sourceDate", "2027-01-01"); return root; },
                root -> { ref(root).put("reviewedAt", NOW.plusSeconds(1).toString()); return root; },
                root -> { ref(root).put("confidence", "ESTIMATED"); return root; },
                root -> { ref(root).put("identityScope", "MODEL"); return root; },
                root -> { ref(root).put("saleUnit", "RAM_KIT"); ref(root).put("moduleCount", 2); return root; },
                root -> { ref(root).put("rangeLowKrw", 200000); ref(root).put("rangeHighKrw", 300000); return root; },
                root -> { ref(root).withArray("sourceQuotes").removeAll(); return root; },
                root -> { ((ObjectNode) ref(root).withArray("sourceQuotes").get(0)).put("sourceUrl", "http://example.com/quote"); return root; },
                root -> { ((ObjectNode) ref(root).withArray("sourceQuotes").get(0)).put("canonicalId", UUID.randomUUID().toString()); return root; },
                root -> { root.put("unexpected", true); return root; },
                root -> { root.withArray("items").add(root.withArray("items").get(0).deepCopy()); return root; },
                root -> { ((ObjectNode) root.withArray("items").get(0)).put("canonicalId", priced.identity().canonicalId()); return root; });
        try (var server = new Server()) {
            for (var transform : invalid) {
                server.transform = transform;
                assertThatThrownBy(() -> client(server).fetch(Set.of(unpriced.identity().canonicalId())))
                        .isInstanceOf(RuntimeException.class);
                assertThat(adapter(server).enrich(List.of(view(unpriced.identity())), Map.of()).getFirst().referenceEstimate()).isNull();
            }
        }
    }

    @Test void boundedBodyDeadlineRedirectAndDuplicateJsonDoNotExposeUntrustedResponseInErrors() throws Exception {
        try (var server = new Server()) {
            server.raw = "{\"schemaVersion\":1,\"schemaVersion\":1,\"token\":\"" + TOKEN + "\"}";
            assertThatThrownBy(() -> client(server).fetch(Set.of(unpriced.identity().canonicalId())))
                    .isInstanceOf(RuntimeException.class).hasMessageNotContaining(TOKEN);
            server.raw = " ".repeat(65537);
            assertThatThrownBy(() -> client(server).fetch(Set.of(unpriced.identity().canonicalId())))
                    .isInstanceOf(RuntimeException.class);
            server.raw = null; server.status = 302;
            assertThatThrownBy(() -> client(server).fetch(Set.of(unpriced.identity().canonicalId())))
                    .isInstanceOf(IllegalArgumentException.class);
            server.status = 200; server.delayMs = 300;
            var quick = new SharedReferencePriceClient(server.uri(), Duration.ofMillis(100), 65536, catalog, clock, TOKEN);
            assertThatThrownBy(() -> quick.fetch(Set.of(unpriced.identity().canonicalId())))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("timed out");
        }
    }

    @Test void sourceDateMayRemainUnknownAndSimilarEstimateMustRetainItsOwnMethodAndRange() throws Exception {
        try (var server = new Server()) {
            server.transform = root -> {
                ref(root).putNull("sourceDate");
                ((ObjectNode) ref(root).withArray("sourceQuotes").get(0)).putNull("sourceDate"); return root;
            };
            assertThat(client(server).fetch(Set.of(unpriced.identity().canonicalId())).items().getFirst()
                    .referenceEstimate().sourceDate()).isNull();
            server.transform = root -> {
                var value = ref(root);
                value.put("basis", "SIMILAR_PART_ESTIMATE"); value.put("identityScope", "SIMILAR_SPEC");
                value.put("confidence", "ESTIMATED"); value.put("method", "SPEC_NEIGHBOUR_MEDIAN");
                value.put("rangeLowKrw", 100000); value.put("rangeHighKrw", 200000);
                var source = (ObjectNode) value.withArray("sourceQuotes").get(0);
                source.put("canonicalId", priced.identity().canonicalId()); source.put("modelName", priced.identity().modelName());
                return root;
            };
            var value = client(server).fetch(Set.of(unpriced.identity().canonicalId())).items().getFirst().referenceEstimate();
            assertThat(value.basis()).isEqualTo(Basis.SIMILAR_PART_ESTIMATE);
            assertThat(value.confidence()).isEqualTo(Confidence.ESTIMATED);
            assertThat(value.rangeLowKrw()).isEqualTo(100000L);
        }
    }

    @Test void badRamKitCountIsRejectedBeforeSendingAndServerCannotConvertTwoModuleQuoteToOne() throws Exception {
        var entry = catalog.products().values().stream().filter(e -> e.price() == null
                && e.identity().type() == PartType.RAM).findFirst().orElseThrow();
        try (var server = new Server()) {
            var product = view(entry.identity());
            assertThat(adapter(server).enrich(List.of(product), Map.of(product.id(), entry.identity().moduleCount() + 1))
                    .getFirst().referenceEstimate()).isNull();
            assertThat(server.queries).isEmpty();
            server.transform = root -> { ref(root).put("moduleCount", entry.identity().moduleCount() + 1); return root; };
            assertThatThrownBy(() -> client(server).fetch(Set.of(entry.identity().canonicalId())))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void externalSingleRamModuleMayOnlyBeUsedAsAnExplicitDifferentUnitEstimate() throws Exception {
        var entry = catalog.products().values().stream().filter(e -> e.price() == null
                && e.identity().type() == PartType.RAM && e.identity().moduleCount() > 1).findFirst().orElseThrow();
        try (var server = new Server()) {
            UnaryOperator<ObjectNode> externalModule = root -> {
                var value = ref(root);
                value.put("basis", "SIMILAR_PART_ESTIMATE"); value.put("identityScope", "SIMILAR_SPEC");
                value.put("confidence", "ESTIMATED"); value.put("method", "SPEC_NEIGHBOUR_MEDIAN");
                value.put("rangeLowKrw", 100000); value.put("rangeHighKrw", 200000);
                value.put("notes", "원본은 모듈 1개 가격이며 목표 판매 묶음은 용량에 따라 환산한 추정값입니다.");
                var source = (ObjectNode) value.withArray("sourceQuotes").get(0);
                source.putNull("canonicalId"); source.put("modelName", "외부 DDR4 8GB 단일 모듈");
                source.put("saleUnit", "PRODUCT"); source.put("moduleCount", 1); return root;
            };
            server.transform = externalModule;
            var value = client(server).fetch(Set.of(entry.identity().canonicalId())).items().getFirst().referenceEstimate();
            assertThat(value.saleUnit()).isEqualTo(SaleUnit.RAM_KIT);
            assertThat(value.moduleCount()).isEqualTo(entry.identity().moduleCount());
            assertThat(value.sourceQuotes().getFirst().saleUnit()).isEqualTo(SaleUnit.PRODUCT);
            assertThat(value.sourceQuotes().getFirst().moduleCount()).isEqualTo(1);
            server.transform = root -> {
                externalModule.apply(root); ref(root).put("basis", "MODEL_RETAIL_REFERENCE");
                ref(root).put("identityScope", "MODEL"); ref(root).put("confidence", "VERIFIED_MODEL");
                ref(root).put("method", "DIRECT_MODEL_QUOTE");
                ref(root).putNull("rangeLowKrw"); ref(root).putNull("rangeHighKrw"); return root;
            };
            assertThatThrownBy(() -> client(server).fetch(Set.of(entry.identity().canonicalId())))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unit");
            server.transform = root -> {
                externalModule.apply(root); ((ObjectNode) ref(root).withArray("sourceQuotes").get(0)).put("moduleCount", 0); return root;
            };
            assertThatThrownBy(() -> client(server).fetch(Set.of(entry.identity().canonicalId())))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unit");
        }
    }

    @Test void failedFirstReferenceBatchIsNotRepeatedAndConcurrentRequestsDoNotShareMutableResults() throws Exception {
        try (var server = new Server()) {
            var adapter = adapter(server);
            var entries = catalog.products().values().stream().filter(e -> e.price() == null
                    && e.identity().type() != PartType.RAM).limit(41).map(e -> view(e.identity())).toList();
            server.status = 503;
            assertThat(adapter.enrich(entries, Map.of())).allSatisfy(v -> assertThat(v.referenceEstimate()).isNull());
            assertThat(server.queries).hasSize(1);
            server.status = 200;
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var futures = new ArrayList<java.util.concurrent.Future<CatalogProductView>>();
                for (int i = 0; i < 8; i++) futures.add(executor.submit(() ->
                        adapter.enrich(List.of(view(unpriced.identity())), Map.of()).getFirst()));
                for (var future : futures) assertThat(future.get().referenceEstimate()).isEqualTo(estimate(unpriced.identity()));
            }
        }
    }

    @Test void disabledPublicationAndOldConstructorsPreserveTheExistingCurrentContract() {
        var product = view(unpriced.identity());
        assertThat(SharedReferencePriceAdapter.disabled().enrich(List.of(product), Map.of())).containsExactly(product);
        var gated = new SharedPriceAdapterConfig().referenceAdapter(catalog, URI.create("http://127.0.0.1:1"),
                Duration.ofMillis(100), 65536, clock, "", SharedReferencePriceSnapshot.loadReviewBundled(catalog));
        assertThat(gated.enrich(List.of(product), Map.of())).containsExactly(product);
        var estimate = estimate(unpriced.identity());
        assertThat(product.withReferenceEstimate(estimate).withPrice(priced.price(), null).referenceEstimate()).isEqualTo(estimate);
        assertThat(mapper.readTree(mapper.writeValueAsBytes(priced.price())).properties())
                .extracting(Map.Entry::getKey).containsExactly("amountKrw", "sourceName", "sourceUrl", "observedAt");
    }

    @Test void approvedActiveBundleEnablesReferenceReadsWithTheExistingTeamSettings() throws Exception {
        try (var server = new Server()) {
            var references = new SharedPriceAdapterConfig().referenceAdapter(catalog, server.uri(), Duration.ofSeconds(1),
                    65536, clock, TOKEN);
            var result = references.enrich(List.of(view(unpriced.identity())), Map.of()).getFirst();
            assertThat(result.referenceEstimate()).isEqualTo(estimate(unpriced.identity()));
            assertThat(server.queries).hasSize(1);
        }
    }

    private SharedReferencePriceClient client(Server server) {
        return new SharedReferencePriceClient(server.uri(), Duration.ofSeconds(1), 65536, catalog, clock, TOKEN);
    }
    private SharedReferencePriceAdapter adapter(Server server) { return new SharedReferencePriceAdapter(catalog, client(server)); }
    private ObjectNode ref(ObjectNode root) { return (ObjectNode) root.withArray("items").get(0).get("referenceEstimate"); }
    private CatalogReferenceEstimate estimate(SharedPriceDtos.Identity identity) {
        return new CatalogReferenceEstimate(123456, Basis.HISTORICAL_RETAIL, IdentityScope.EXACT_PRODUCT,
                SaleUnit.valueOf(identity.saleUnit()), identity.moduleCount(), LocalDate.of(2020, 7, 1),
                NOW.minus(Duration.ofDays(10)), Confidence.VERIFIED_MODEL, Method.DIRECT_MODEL_QUOTE,
                null, null, List.of(new SourceQuote(identity.canonicalId(), identity.modelName(), 123456,
                    "DANAWA", "https://prod.danawa.com/info/?pcode=12345", LocalDate.of(2020, 7, 1),
                    SaleUnit.valueOf(identity.saleUnit()), identity.moduleCount())),
                "과거 모델 판매가 참고입니다.");
    }
    private CatalogProductView view(SharedPriceDtos.Identity identity) {
        return new CatalogProductView("local-private-" + UUID.randomUUID(), identity.type(), identity.manufacturer(),
                identity.modelName(), identity.partNumber(), identity.verificationStatus(), identity.active(), NOW, NOW,
                new CatalogProductView.ReferencePrice(null, CatalogPriceStatus.UNCONFIRMED, NOW), null,
                identity.canonicalId(), null, identity.identityKind(), identity.role());
    }
    private final class Server implements AutoCloseable {
        final HttpServer server;
        final CopyOnWriteArrayList<String> queries = new CopyOnWriteArrayList<>();
        final CopyOnWriteArrayList<String> headers = new CopyOnWriteArrayList<>();
        volatile int status = 200;
        volatile int currentStatus = 200;
        volatile int delayMs;
        volatile String raw;
        volatile UnaryOperator<ObjectNode> transform = UnaryOperator.identity();
        Server() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.createContext("/", exchange -> {
                boolean current = exchange.getRequestURI().getPath().equals("/api/v2/prices");
                String query = exchange.getRequestURI().getRawQuery();
                var ids = query.substring("canonicalIds=".length()).split(",");
                byte[] body;
                if (current) {
                    var items = new ArrayList<SharedPriceDtos.Item>();
                    for (var id : ids) {
                        var entry = catalog.products().get(id);
                        items.add(new SharedPriceDtos.Item(id, entry.identity(), entry.price(), entry.price() == null
                                ? SharedPriceDtos.Status.NO_PRICE : SharedPriceDtos.Status.OK,
                                SharedPriceFreshness.defaults().classify(entry.price() == null ? null : entry.price().observedAt(), NOW)));
                    }
                    body = mapper.writeValueAsBytes(new SharedPriceDtos.EnvelopeV2(2, catalog.version(), catalog.priceVersion(),
                            NOW, SharedPriceFreshness.defaults().toView(), items));
                } else {
                    queries.add(query);
                    headers.add(exchange.getRequestHeaders().getFirst("Authorization").replace("Bearer ", "") + ":"
                            + exchange.getRequestHeaders().getFirst("X-Catalog-Version"));
                    var items = new ArrayList<SharedReferencePriceDtos.Item>();
                    for (var id : ids) {
                        var entry = catalog.products().get(id);
                        items.add(new SharedReferencePriceDtos.Item(id, entry.identity(), estimate(entry.identity()),
                                SharedReferencePriceDtos.Status.OK));
                    }
                    var value = new SharedReferencePriceDtos.Envelope(1, catalog.version(), "references-v1-" + "b".repeat(64), NOW, items);
                    body = raw == null ? mapper.writeValueAsBytes(transform.apply((ObjectNode) mapper.readTree(mapper.writeValueAsBytes(value))))
                            : raw.getBytes(StandardCharsets.UTF_8);
                }
                try {
                    if (!current && delayMs > 0) Thread.sleep(delayMs);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(current ? currentStatus : status, body.length);
                    exchange.getResponseBody().write(body);
                } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                finally { exchange.close(); }
            });
            server.start();
        }
        URI uri() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort()); }
        @Override public void close() { server.stop(0); }
    }
}
