package com.pcupgradelab.catalog.shared;

import com.pcupgradelab.catalog.CatalogPriceStatus;
import com.pcupgradelab.catalog.CatalogProductView;
import com.pcupgradelab.catalog.RamSpecRepository;
import com.pcupgradelab.pc.PartType;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class SharedPriceAdapterTests {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final SharedPriceFreshness policy = SharedPriceFreshness.defaults();
    private SharedCatalogSnapshot snapshot;
    private SharedCatalogSnapshot.Entry priced;
    private SharedCatalogSnapshot.Entry unpriced;
    private MutableClock clock;

    @BeforeEach void fixture() {
        snapshot = SharedCatalogSnapshot.load();
        priced = snapshot.products().values().stream().filter(e -> e.price() != null).findFirst().orElseThrow();
        unpriced = snapshot.products().values().stream().filter(e -> e.price() == null).findFirst().orElseThrow();
        clock = new MutableClock(priced.price().observedAt().plus(Duration.ofHours(24)));
    }

    @Test void disabledAndOutOfScopeRemainLocalWithoutSendingPrivateIdentifiers() throws Exception {
        var local = view(priced.identity()).withPrice(priced.price(), null);
        assertThat(SharedPriceAdapter.disabled().enrich(List.of(local))).containsExactly(local);
        try (var server = new ScriptedServer()) {
            var unrelated = new CatalogProductView(local.id(), local.type(), local.manufacturer(), local.modelName(),
                    local.partNumber(), local.verificationStatus(), local.active(), local.createdAt(), local.updatedAt(),
                    local.referencePrice(), local.currentPrice());
            var result = adapter(server).enrich(List.of(unrelated)).getFirst();
            assertThat(result.currentPrice()).isEqualTo(priced.price());
            assertThat(result.priceStatus().origin()).isEqualTo("LOCAL");
            assertThat(result.priceStatus().lookupStatus()).isEqualTo("NOT_IN_SCOPE");
            assertThat(result.priceStatus().includedInTotal()).isTrue();
            assertThat(server.requests).hasValue(0);
        }
    }

    @Test void freshStaleAndExpiredKeepOriginalObservationAndUseTheLocalClockBoundaries() throws Exception {
        try (var server = new ScriptedServer()) {
            var adapter = adapter(server);
            var product = view(priced.identity());
            for (var age : List.of(Duration.ofHours(48).minusSeconds(1), Duration.ofHours(48), Duration.ofDays(7))) {
                clock.now = priced.price().observedAt().plus(age);
                server.reply.set(Reply.json(envelope(priced.identity().canonicalId(), SharedPriceDtos.Status.OK)));
                var result = adapter.enrich(List.of(product), counts(product)).getFirst();
                assertThat(result.currentPrice()).isEqualTo(priced.price());
                assertThat(result.priceStatus().freshness()).isEqualTo(policy.classify(priced.price().observedAt(), clock.now).name());
                assertThat(result.priceStatus().includedInTotal()).isEqualTo(age.compareTo(Duration.ofDays(7)) < 0);
                assertThat(result.priceStatus().checkedAt()).isEqualTo(clock.now);
                assertThat(result.priceStatus().lastSuccessAt()).isEqualTo(clock.now);
            }
            assertThat(server.queries).allSatisfy(q -> {
                assertThat(q).isEqualTo("canonicalIds=" + priced.identity().canonicalId());
                assertThat(q).doesNotContain(product.id());
            });
        }
    }

    @Test void unavailableKeepsOnlyTheLastValidObservationAndAlwaysExcludesTheTotal() throws Exception {
        try (var server = new ScriptedServer()) {
            var adapter = adapter(server); var product = view(priced.identity());
            server.reply.set(Reply.json(envelope(product.canonicalId(), SharedPriceDtos.Status.OK)));
            var first = adapter.enrich(List.of(product), counts(product)).getFirst();
            clock.now = priced.price().observedAt().plus(Duration.ofDays(8));
            server.reply.set(new Reply(503, "application/json", "{}", 0, false));
            var cached = adapter.enrich(List.of(product), counts(product)).getFirst();
            assertThat(cached.currentPrice()).isEqualTo(priced.price());
            assertThat(cached.priceStatus().lookupStatus()).isEqualTo("UNAVAILABLE");
            assertThat(cached.priceStatus().freshness()).isEqualTo("EXPIRED");
            assertThat(cached.priceStatus().lastSuccessAt()).isEqualTo(first.priceStatus().lastSuccessAt());
            assertThat(cached.priceStatus().includedInTotal()).isFalse();
            var empty = adapter(server).enrich(List.of(product.withPrice(priced.price(), null)), counts(product)).getFirst();
            assertThat(empty.currentPrice()).isNull();
            assertThat(empty.priceStatus().freshness()).isNull();
            assertThat(empty.priceStatus().lastSuccessAt()).isNull();
            assertThat(empty.priceStatus().catalogVersion()).isEqualTo(snapshot.version());
        }
    }

    @Test void noPriceAndUnknownAreDistinctAndDoNotSubstituteALocalPrice() throws Exception {
        try (var server = new ScriptedServer()) {
            var adapter = adapter(server); var product = view(unpriced.identity()).withPrice(priced.price(), null);
            server.reply.set(Reply.json(envelope(product.canonicalId(), SharedPriceDtos.Status.NO_PRICE)));
            var result = adapter.enrich(List.of(product), counts(product)).getFirst();
            assertThat(result.currentPrice()).isNull(); assertThat(result.priceStatus().lookupStatus()).isEqualTo("NO_PRICE");
            assertThat(result.priceStatus().freshness()).isEqualTo("NO_PRICE");
            server.reply.set(Reply.json(envelope(product.canonicalId(), SharedPriceDtos.Status.UNKNOWN_PRODUCT)));
            result = adapter.enrich(List.of(product), counts(product)).getFirst();
            assertThat(result.priceStatus().lookupStatus()).isEqualTo("UNKNOWN_PRODUCT");
            assertThat(result.priceStatus().includedInTotal()).isFalse();
            server.reply.set(new Reply(503, "application/json", "{}", 0, false));
            result = adapter.enrich(List.of(product), counts(product)).getFirst();
            assertThat(result.currentPrice()).isNull(); assertThat(result.priceStatus().lookupStatus()).isEqualTo("UNAVAILABLE");
            assertThat(result.priceStatus().freshness()).isEqualTo("NO_PRICE");
        }
    }

    @Test void localIdentityAndActualRamModuleCountMismatchesFailBeforeAnyNetworkRequest() throws Exception {
        try (var server = new ScriptedServer()) {
            var good = view(priced.identity());
            var bad = new CatalogProductView(good.id(), good.type(), good.manufacturer(), good.modelName(), "wrong-PN",
                    good.verificationStatus(), good.active(), good.createdAt(), good.updatedAt(), good.referencePrice(),
                    priced.price(), good.canonicalId(), good.modelId(), good.identityKind(), good.role());
            var result = adapter(server).enrich(List.of(bad), Map.of()).getFirst();
            assertThat(result.currentPrice()).isNull(); assertThat(result.priceStatus().lookupStatus()).isEqualTo("UNAVAILABLE");
            var memory = snapshot.products().values().stream().filter(e -> e.identity().type() == PartType.RAM).findFirst().orElseThrow();
            var kit = view(memory.identity());
            result = adapter(server).enrich(List.of(kit), Map.of(kit.id(), memory.identity().moduleCount() + 1)).getFirst();
            assertThat(result.priceStatus().lookupStatus()).isEqualTo("UNAVAILABLE");
            assertThat(server.requests).hasValue(0);
        }
    }

    @Test void strictContractRejectsChangedIdsIdentityPriceUnitPolicyVersionTimeAndMalformedJson() throws Exception {
        try (var server = new ScriptedServer()) {
            var good = envelope(priced.identity().canonicalId(), SharedPriceDtos.Status.OK);
            List<UnaryOperator<ObjectNode>> mutations = List.of(
                    n -> { n.put("schemaVersion", 2); return n; },
                    n -> { n.put("catalogVersion", "different"); return n; },
                    n -> { n.put("servedAt", clock.now.plusSeconds(1).toString()); return n; },
                    n -> { ((ObjectNode)n.get("policy")).put("staleAfterSeconds", 1); return n; },
                    n -> { ((ObjectNode)n.get("items").get(0)).put("canonicalId", UUID.randomUUID().toString()); return n; },
                    n -> { ((ObjectNode)n.get("items").get(0).get("product")).put("partNumber", "wrong-PN"); return n; },
                    n -> { ((ObjectNode)n.get("items").get(0).get("product")).put("saleUnit",
                            "RAM_KIT".equals(priced.identity().saleUnit()) ? "PRODUCT" : "RAM_KIT"); return n; },
                    n -> { ((ObjectNode)n.get("items").get(0).get("price")).put("amountKrw", 0); return n; },
                    n -> { ((ObjectNode)n.get("items").get(0).get("price")).put("observedAt", clock.now.plusSeconds(1).toString()); return n; },
                    n -> { ((ObjectNode)n.get("items").get(0).get("price")).put("sourceUrl", "https://example.com/wrong"); return n; },
                    n -> { n.put("unexpected", true); return n; },
                    n -> { ((ObjectNode)n.get("items").get(0).get("price")).put("amountKrw", "100"); return n; });
            for (var mutation : mutations) {
                server.reply.set(Reply.json(mapper.writeValueAsString(mutation.apply((ObjectNode)mapper.readTree(good)))));
                assertThatThrownBy(() -> client(server, 2000, 65536).fetch(Set.of(priced.identity().canonicalId())))
                        .isInstanceOf(RuntimeException.class);
            }
            for (String malformed : List.of(good.replaceFirst("\\{", "{\"schemaVersion\":1,"), good + " {}",
                    good.replace("\"amountKrw\":" + priced.price().amountKrw(), "\"amountKrw\":1.5"))) {
                server.reply.set(Reply.json(malformed));
                assertThatThrownBy(() -> client(server, 2000, 65536).fetch(Set.of(priced.identity().canonicalId())))
                        .isInstanceOf(RuntimeException.class);
            }
            var duplicate = (ObjectNode)mapper.readTree(good);
            ((tools.jackson.databind.node.ArrayNode)duplicate.get("items")).add(duplicate.get("items").get(0).deepCopy());
            server.reply.set(Reply.json(mapper.writeValueAsString(duplicate)));
            assertThatThrownBy(() -> client(server, 2000, 65536).fetch(Set.of(priced.identity().canonicalId())))
                    .isInstanceOf(RuntimeException.class);
        }
    }

    @Test void transportDoesNotFollowRedirectsAndBoundsTheEntireBodySizeAndTime() throws Exception {
        try (var server = new ScriptedServer()) {
            for (var reply : List.of(new Reply(302, "application/json", "{}", 0, false),
                    new Reply(200, "text/html", "{}", 0, false),
                    new Reply(200, "application/json", "x".repeat(2000), 0, false),
                    new Reply(200, "application/json", "{}", 400, true))) {
                server.reply.set(reply);
                long started = System.nanoTime();
                assertThatThrownBy(() -> client(server, reply.delayMs() > 0 ? 100 : 1000, 1024)
                        .fetch(Set.of(priced.identity().canonicalId())))
                        .isInstanceOf(RuntimeException.class);
                assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
            }
            assertThat(server.requests).hasValue(4);
        }
    }

    @Test void aPartlyInvalidBatchNeverPopulatesEvenTheValidItemsCache() throws Exception {
        clock.now = snapshot.products().values().stream().filter(e -> e.price() != null)
                .map(e -> e.price().observedAt()).max(java.util.Comparator.naturalOrder()).orElseThrow().plusSeconds(3600);
        var scope = snapshot.products().values().stream().filter(e -> e.price() != null).limit(2).toList();
        var products = scope.stream().map(e -> view(e.identity())).toList();
        var items = scope.stream().map(e -> new SharedPriceDtos.Item(e.identity().canonicalId(), e.identity(), e.price(),
                SharedPriceDtos.Status.OK, policy.classify(e.price().observedAt(), clock.now))).toList();
        var good = mapper.writeValueAsString(new SharedPriceDtos.Envelope(1, snapshot.version(), clock.now, policy.toView(), items));
        var broken = (ObjectNode)mapper.readTree(good);
        ((ObjectNode)broken.get("items").get(1).get("product")).put("partNumber", "unapproved");
        var counts = new java.util.HashMap<String, Integer>();
        products.forEach(p -> counts.putAll(counts(p)));
        try (var server = new ScriptedServer()) {
            var adapter = adapter(server);
            server.reply.set(Reply.json(mapper.writeValueAsString(broken)));
            assertThat(adapter.enrich(products, counts)).allSatisfy(p -> {
                assertThat(p.currentPrice()).isNull(); assertThat(p.priceStatus().lastSuccessAt()).isNull();
                assertThat(p.priceStatus().lookupStatus()).isEqualTo("UNAVAILABLE");
            });
            server.reply.set(new Reply(503, "application/json", "{}", 0, false));
            assertThat(adapter.enrich(products, counts)).allSatisfy(p -> assertThat(p.currentPrice()).isNull());
            server.reply.set(Reply.json(good));
            assertThat(adapter.enrich(products, counts)).allSatisfy(p -> assertThat(p.priceStatus().lookupStatus()).isEqualTo("OK"));
            server.reply.set(Reply.json(mapper.writeValueAsString(broken)));
            assertThat(adapter.enrich(products, counts)).allSatisfy(p -> {
                assertThat(p.currentPrice()).isEqualTo(snapshot.products().get(p.canonicalId()).price());
                assertThat(p.priceStatus().lookupStatus()).isEqualTo("UNAVAILABLE");
                assertThat(p.priceStatus().includedInTotal()).isFalse();
            });
        }
    }

    @Test void sixParallelTimeoutsDoNotSerializeBehindTheAdapterCache() throws Exception {
        try (var server = new ScriptedServer(); var workers = Executors.newFixedThreadPool(6)) {
            var product = view(priced.identity());
            var adapter = new SharedPriceAdapter(snapshot, client(server, 500, 65536), clock, policy, mock(RamSpecRepository.class));
            server.reply.set(Reply.json(envelope(product.canonicalId(), SharedPriceDtos.Status.OK)));
            assertThat(adapter.enrich(List.of(product), counts(product)).getFirst().priceStatus().lookupStatus()).isEqualTo("OK");
            server.reply.set(new Reply(200, "application/json", "{}", 2000, true));
            var ready = new CountDownLatch(6); var start = new CountDownLatch(1);
            var tasks = java.util.stream.IntStream.range(0, 6).mapToObj(i -> workers.submit(() -> {
                ready.countDown(); start.await(); return adapter.enrich(List.of(product), counts(product)).getFirst();
            })).toList();
            assertThat(ready.await(2, TimeUnit.SECONDS)).isTrue();
            long begun = System.nanoTime(); start.countDown();
            for (var task : tasks) {
                var result = task.get(2, TimeUnit.SECONDS);
                assertThat(result.priceStatus().lookupStatus()).isEqualTo("UNAVAILABLE");
                assertThat(result.priceStatus().includedInTotal()).isFalse();
                assertThat(result.currentPrice()).isEqualTo(priced.price());
            }
            assertThat(Duration.ofNanos(System.nanoTime() - begun)).isLessThan(Duration.ofSeconds(2));
        }
    }

    @Test void unsafeBaseUrlsAndOutOfScopeRequestsAreRejected() throws Exception {
        for (String url : List.of("http://localhost:1234", "http://example.com", "https://user:pass@example.com",
                "https://example.com/?x=1", "https://example.com/#x", "https://example.com/path", "http://127.999.0.1"))
            assertThatThrownBy(() -> SharedPriceClient.validateBaseUrl(URI.create(url))).isInstanceOf(IllegalArgumentException.class);
        assertThat(SharedPriceClient.validateBaseUrl(URI.create("https://prices.example.com"))).isNotNull();
        assertThat(SharedPriceClient.validateBaseUrl(URI.create("http://[::1]:1234"))).isNotNull();
        try (var server = new ScriptedServer()) {
            assertThatThrownBy(() -> client(server, 2000, 65536).fetch(Set.of(UUID.randomUUID().toString())))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(server.requests).hasValue(0);
        }
    }

    @Test void optInConfigurationRequiresAnExplicitOriginAndValidTimeAndSizeBounds() {
        var config = new SharedPriceAdapterConfig(); var ram = mock(RamSpecRepository.class);
        assertThat(config.sharedPriceAdapter(false, "", 2000, 65536, 172800, 604800, clock, ram)).isNotNull();
        for (String origin : List.of("", " http://127.0.0.1:8081", "http://localhost:8081"))
            assertThatThrownBy(() -> config.sharedPriceAdapter(true, origin, 2000, 65536, 172800, 604800, clock, ram))
                    .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> config.sharedPriceAdapter(true, "http://127.0.0.1:8081", 99, 65536, 172800, 604800, clock, ram))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> config.sharedPriceAdapter(true, "http://127.0.0.1:8081", 2000, 262145, 172800, 604800, clock, ram))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> config.sharedPriceAdapter(true, "http://127.0.0.1:8081", 2000, 65536, 0, 604800, clock, ram))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> config.sharedPriceAdapter(true, "http://127.0.0.1:8081", 2000, 65536, 172800, 172800, clock, ram))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private String envelope(String canonicalId, SharedPriceDtos.Status status) {
        var expected = snapshot.products().get(canonicalId);
        var price = status == SharedPriceDtos.Status.OK ? expected.price() : null;
        var identity = status == SharedPriceDtos.Status.UNKNOWN_PRODUCT ? null : expected.identity();
        return mapper.writeValueAsString(new SharedPriceDtos.Envelope(1, snapshot.version(), clock.now, policy.toView(),
                List.of(new SharedPriceDtos.Item(canonicalId, identity, price, status,
                        policy.classify(price == null ? null : price.observedAt(), clock.now)))));
    }
    private SharedPriceClient client(ScriptedServer server, long timeoutMs, int maxBytes) {
        return new SharedPriceClient(server.url, Duration.ofMillis(timeoutMs), maxBytes, snapshot, clock, policy);
    }
    private SharedPriceAdapter adapter(ScriptedServer server) {
        return new SharedPriceAdapter(snapshot, client(server, 2000, 65536), clock, policy, mock(RamSpecRepository.class));
    }
    private Map<String, Integer> counts(CatalogProductView product) {
        var identity = snapshot.products().get(product.canonicalId()).identity();
        return product.type() == PartType.RAM ? Map.of(product.id(), identity.moduleCount()) : Map.of();
    }
    private CatalogProductView view(SharedPriceDtos.Identity p) {
        return new CatalogProductView(UUID.randomUUID().toString(), p.type(), p.manufacturer(), p.modelName(), p.partNumber(),
                p.verificationStatus(), p.active(), clock.now, clock.now,
                new CatalogProductView.ReferencePrice(null, CatalogPriceStatus.UNCONFIRMED, clock.now), null,
                p.canonicalId(), null, p.identityKind(), p.role());
    }
    private record Reply(int status, String contentType, String body, long delayMs, boolean headersFirst) {
        static Reply json(String body) { return new Reply(200, "application/json", body, 0, false); }
    }
    private static final class ScriptedServer implements AutoCloseable {
        final HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final URI url = URI.create("http://127.0.0.1:" + http.getAddress().getPort());
        final AtomicReference<Reply> reply = new AtomicReference<>(new Reply(503, "application/json", "{}", 0, false));
        final AtomicInteger requests = new AtomicInteger();
        final List<String> queries = new ArrayList<>();
        final ExecutorService workers = Executors.newFixedThreadPool(8);
        ScriptedServer() throws Exception {
            http.setExecutor(workers);
            http.createContext("/", exchange -> {
                requests.incrementAndGet(); synchronized (queries) { queries.add(exchange.getRequestURI().getRawQuery()); }
                var r = reply.get(); byte[] body = r.body().getBytes(StandardCharsets.UTF_8);
                try {
                    exchange.getResponseHeaders().set("Content-Type", r.contentType());
                    if (r.status() == 302) exchange.getResponseHeaders().set("Location", url + "/redirect");
                    if (r.headersFirst()) { exchange.sendResponseHeaders(r.status(), 0); exchange.getResponseBody().flush(); }
                    if (r.delayMs() > 0) Thread.sleep(r.delayMs());
                    if (!r.headersFirst()) exchange.sendResponseHeaders(r.status(), body.length);
                    exchange.getResponseBody().write(body);
                } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                finally { exchange.close(); }
            });
            http.start();
        }
        @Override public void close() { http.stop(0); workers.shutdownNow(); }
    }
    static final class MutableClock extends Clock {
        Instant now; MutableClock(Instant now) { this.now = now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
