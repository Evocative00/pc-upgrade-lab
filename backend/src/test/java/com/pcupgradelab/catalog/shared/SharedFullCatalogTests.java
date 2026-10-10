package com.pcupgradelab.catalog.shared;

import com.pcupgradelab.catalog.CatalogPriceStatus;
import com.pcupgradelab.catalog.CatalogProductView;
import com.pcupgradelab.catalog.RamSpecRepository;
import com.pcupgradelab.pc.PartType;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class SharedFullCatalogTests {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private final SharedCatalogSnapshot snapshot = SharedCatalogSnapshot.loadFull();
    private final SharedPriceFreshness policy = SharedPriceFreshness.defaults();
    private final Clock clock = Clock.fixed(snapshot.products().values().stream().filter(e -> e.price() != null)
            .map(e -> e.price().observedAt()).max(Instant::compareTo).orElseThrow().plusSeconds(3600), ZoneOffset.UTC);

    @Test void fullCatalogPreservesAllSourceIdentitiesAndTheOriginalReviewedPriceTimes() {
        assertThat(SharedCatalogSnapshot.load().version()).isEqualTo(snapshot.version());
        assertThat(snapshot.isFull()).isTrue(); assertThat(snapshot.schemaVersion()).isEqualTo(2);
        assertThat(snapshot.apiPath()).isEqualTo("/api/v2/prices");
        assertThat(snapshot.products()).hasSize(307);
        assertThat(snapshot.products().values().stream().filter(e -> e.price() != null)).hasSize(74);
        assertThat(snapshot.products().values().stream().filter(e -> e.identity().identityKind().name().equals("LEGACY_UNCLASSIFIED")))
                .hasSize(293);
        var pilot = SharedCatalogSnapshot.loadPilot();
        pilot.products().forEach((id, entry) -> assertThat(snapshot.products().get(id)).isEqualTo(entry));
        assertThat(snapshot.version()).matches("all-catalog-v1-[0-9a-f]{64}");
        assertThat(snapshot.priceVersion()).matches("prices-v1-[0-9a-f]{64}");
    }

    @Test void anApprovedPriceChangeOnlyChangesThePriceRevision(@TempDir Path temporary) throws Exception {
        Path directory = Files.createDirectories(temporary.resolve("data/catalog-shared"));
        String identities = resource("all-catalog-identities-2026-10-10.json");
        String original = resource("approved-prices-2026-10-10.json");
        var changed = (ObjectNode) MAPPER.readTree(original);
        var price = (ObjectNode) changed.path("items").get(0).path("price");
        price.put("amountKrw", price.path("amountKrw").longValue() + 1);
        Files.writeString(directory.resolve("all-catalog-identities-2026-10-10.json"), identities);
        Files.writeString(directory.resolve("approved-prices-2026-10-10.json"), MAPPER.writeValueAsString(changed));
        var updated = SharedCatalogSnapshot.loadFull(temporary, directory.resolve("approved-prices-2026-10-10.json"));
        assertThat(updated.version()).isEqualTo(snapshot.version());
        assertThat(updated.priceVersion()).isNotEqualTo(snapshot.priceVersion());
        assertThat(updated.products().keySet()).isEqualTo(snapshot.products().keySet());
    }

    @Test void filesystemExportsUseTheActive75PriceBundleAndPreserveTheOriginalBaseline(@TempDir Path temporary) throws Exception {
        Path directory = baselineDirectory(temporary);
        Path approved = Path.of("../data/catalog-review/all-catalog-price-proposal-2026-10-10.json").toAbsolutePath().normalize();
        Files.copy(approved, temporary.resolve(SharedCatalogSnapshot.ACTIVE_PRICES_FILE));
        var active = SharedCatalogSnapshot.loadFull(temporary);
        assertThat(active.products().values().stream().filter(e -> e.price() != null)).hasSize(75);
        assertThat(active.version()).isEqualTo(snapshot.version());
        assertThat(active.priceVersion()).isEqualTo(SharedCatalogSnapshot.loadFull(temporary, approved).priceVersion());
        assertThat(active.priceVersion()).isNotEqualTo(snapshot.priceVersion());
        assertThat(active.products().values().stream().filter(e -> e.identity().modelName().equals("S2721DGF")))
                .singleElement().satisfies(e -> assertThat(e.price().amountKrw()).isEqualTo(976500));
        assertThat(Files.readString(directory.resolve("approved-prices-2026-10-10.json")))
                .isEqualTo(resource("approved-prices-2026-10-10.json"));
        assertThat(SharedCatalogSnapshot.loadFull().priceVersion()).isEqualTo(snapshot.priceVersion());
    }

    @Test void aMissingActivePriceBundleCannotSilentlyRestore74Prices(@TempDir Path temporary) throws Exception {
        Path directory = baselineDirectory(temporary);
        assertThatThrownBy(() -> SharedCatalogSnapshot.loadFull(temporary)).isInstanceOf(IllegalArgumentException.class);
        assertThat(SharedCatalogSnapshot.loadFull(temporary, directory.resolve("approved-prices-2026-10-10.json"))
                .products().values().stream().filter(e -> e.price() != null)).hasSize(74);
    }

    @Test void anInvalidActivePriceBundleCannotFallBackToTheOriginalPrices(@TempDir Path temporary) throws Exception {
        baselineDirectory(temporary);
        Files.writeString(temporary.resolve(SharedCatalogSnapshot.ACTIVE_PRICES_FILE), "{");
        assertThatThrownBy(() -> SharedCatalogSnapshot.loadFull(temporary)).isInstanceOf(RuntimeException.class);
        assertThat(SharedCatalogSnapshot.loadFull().priceVersion()).isEqualTo(snapshot.priceVersion());
    }

    private static Path baselineDirectory(Path temporary) throws Exception {
        Path directory = Files.createDirectories(temporary.resolve("data/catalog-shared"));
        for (String file : List.of("all-catalog-identities-2026-10-10.json", "approved-prices-2026-10-10.json"))
            Files.writeString(directory.resolve(file), resource(file));
        return directory;
    }

    @Test void priceOnlyRefreshesAndNoPriceTransitionsDoNotRequireANewBackendSnapshot() throws Exception {
        var unpriced = snapshot.products().values().stream().filter(e -> e.price() == null).findFirst().orElseThrow();
        var priced = snapshot.products().values().stream().filter(e -> e.price() != null).findFirst().orElseThrow();
        try (var server = new FullServer()) {
            var next = new CatalogProductView.CurrentPrice(123456, "DANAWA", "https://prod.danawa.com/info/?pcode=123",
                    clock.instant().minusSeconds(1));
            server.change.set(items -> items.stream().map(i -> i.canonicalId().equals(unpriced.identity().canonicalId())
                    ? new SharedPriceDtos.Item(i.canonicalId(), i.product(), next, SharedPriceDtos.Status.OK,
                            SharedPriceDtos.Freshness.FRESH) : i).toList());
            assertThat(client(server).fetch(Set.of(unpriced.identity().canonicalId())).items().getFirst().price()).isEqualTo(next);
            server.change.set(items -> items.stream().map(i -> new SharedPriceDtos.Item(i.canonicalId(), i.product(), null,
                    SharedPriceDtos.Status.NO_PRICE, SharedPriceDtos.Freshness.NO_PRICE)).toList());
            assertThat(client(server).fetch(Set.of(priced.identity().canonicalId())).items().getFirst().price()).isNull();
            server.change.set(items -> items.stream().map(i -> new SharedPriceDtos.Item(i.canonicalId(), i.product(), next,
                    SharedPriceDtos.Status.OK, SharedPriceDtos.Freshness.FRESH)).toList());
            assertThat(client(server).fetch(Set.of(priced.identity().canonicalId())).items().getFirst().price()).isEqualTo(next);
            assertThat(server.paths).containsOnly("/api/v2/prices");
        }
    }

    @Test void invalidDynamicObservationsAndChangedCatalogIdentityRemainUnavailable() throws Exception {
        String id = snapshot.products().keySet().iterator().next();
        try (var server = new FullServer()) {
            for (var invalid : List.of(
                    new CatalogProductView.CurrentPrice(0, "DANAWA", "https://prod.danawa.com/info/?pcode=123", clock.instant()),
                    new CatalogProductView.CurrentPrice(999999999999L + 1, "DANAWA", "https://prod.danawa.com/info/?pcode=123", clock.instant()),
                    new CatalogProductView.CurrentPrice(100, "OTHER", "https://prod.danawa.com/info/?pcode=123", clock.instant()),
                    new CatalogProductView.CurrentPrice(100, "DANAWA", "https://prices.example.org/", clock.instant()),
                    new CatalogProductView.CurrentPrice(100, "DANAWA", "https://prod.danawa.com/info/?pcode=123", clock.instant().plusSeconds(1)))) {
                server.change.set(items -> List.of(new SharedPriceDtos.Item(id, items.getFirst().product(), invalid,
                        SharedPriceDtos.Status.OK, SharedPriceDtos.Freshness.FRESH)));
                assertThatThrownBy(() -> client(server).fetch(Set.of(id))).isInstanceOf(IllegalArgumentException.class);
            }
            server.change.set(items -> items); server.catalogVersion = "all-catalog-v1-" + "0".repeat(64);
            assertThatThrownBy(() -> client(server).fetch(Set.of(id))).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void allProductsUseBoundedChunksAndOneFailedChunkDoesNotDiscardOtherResults() throws Exception {
        var products = snapshot.products().values().stream().map(e -> view(e.identity())).toList();
        var counts = new LinkedHashMap<String, Integer>();
        products.stream().filter(p -> p.type() == PartType.RAM).forEach(p -> counts.put(p.id(),
                snapshot.products().get(p.canonicalId()).identity().moduleCount()));
        try (var server = new FullServer()) {
            server.failedRequest = 2;
            var result = new SharedPriceAdapter(snapshot, client(server), clock, policy, mock(RamSpecRepository.class))
                    .enrich(products, counts);
            assertThat(server.requests).hasValue(7);
            assertThat(server.requestSizes).allSatisfy(size -> assertThat(size).isBetween(1, 50));
            assertThat(server.responseSizes).allSatisfy(size -> assertThat(size).isLessThan(65536));
            assertThat(result).allSatisfy(p -> assertThat(p.priceStatus().origin()).isEqualTo("SHARED"));
            assertThat(result.stream().filter(p -> p.priceStatus().lookupStatus().equals("UNAVAILABLE"))).hasSize(50);
            assertThat(result.stream().filter(p -> !p.priceStatus().lookupStatus().equals("UNAVAILABLE"))).hasSize(257);
            assertThat(result.stream().filter(p -> p.priceStatus().lookupStatus().equals("UNAVAILABLE")))
                    .allSatisfy(p -> { assertThat(p.currentPrice()).isNull(); assertThat(p.priceStatus().includedInTotal()).isFalse(); });
        }
    }

    @Test void aFiveSecondServerClockLeadIsAllowedButLargerLeadsAndFutureObservationsAreRejected() throws Exception {
        String id = snapshot.products().keySet().iterator().next();
        try (var server = new FullServer()) {
            for (var skew : List.of(Duration.ZERO, Duration.ofSeconds(5).minusNanos(1), Duration.ofSeconds(5))) {
                server.servedAt = clock.instant().plus(skew);
                assertThat(client(server).fetch(Set.of(id)).items()).hasSize(1);
            }
            server.servedAt = clock.instant().plusSeconds(5).plusNanos(1);
            assertThatThrownBy(() -> client(server).fetch(Set.of(id))).isInstanceOf(IllegalArgumentException.class);
            server.servedAt = clock.instant();
            var future = new CatalogProductView.CurrentPrice(100, "DANAWA", "https://prod.danawa.com/info/?pcode=123",
                    server.servedAt.plusSeconds(1));
            server.change.set(items -> List.of(new SharedPriceDtos.Item(id, items.getFirst().product(), future,
                    SharedPriceDtos.Status.OK, SharedPriceDtos.Freshness.FRESH)));
            assertThatThrownBy(() -> client(server).fetch(Set.of(id))).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void aClientClockFiveSecondsBehindCanReadAllSevenChunks() throws Exception {
        var localClock = Clock.fixed(clock.instant().minusSeconds(5), ZoneOffset.UTC);
        var products = snapshot.products().values().stream().map(e -> view(e.identity())).toList();
        var counts = new LinkedHashMap<String, Integer>();
        products.stream().filter(p -> p.type() == PartType.RAM).forEach(p -> counts.put(p.id(),
                snapshot.products().get(p.canonicalId()).identity().moduleCount()));
        try (var server = new FullServer()) {
            var localClient = new SharedPriceClient(server.origin, Duration.ofSeconds(2), 65536, snapshot, localClock, policy);
            var results = new SharedPriceAdapter(snapshot, localClient, localClock, policy, mock(RamSpecRepository.class))
                    .enrich(products, counts);
            assertThat(server.requests).hasValue(7);
            assertThat(results).hasSize(307).allSatisfy(p -> {
                assertThat(p.priceStatus().lookupStatus()).isIn("OK", "NO_PRICE");
                assertThat(p.priceStatus().freshness()).isEqualTo(policy.classify(
                        p.currentPrice() == null ? null : p.currentPrice().observedAt(), clock.instant()).name());
            });
        }
    }

    @Test void aStaleServerTimestampCannotMakeAnOldObservationFreshAgain() throws Exception {
        String id = snapshot.products().values().stream().filter(e -> e.price() == null)
                .findFirst().orElseThrow().identity().canonicalId();
        try (var server = new FullServer()) {
            server.change.set(items -> List.of(new SharedPriceDtos.Item(id, items.getFirst().product(),
                    new CatalogProductView.CurrentPrice(100, "DANAWA", "https://prod.danawa.com/info/?pcode=123", server.servedAt),
                    SharedPriceDtos.Status.OK, SharedPriceDtos.Freshness.FRESH)));
            for (var behind : List.of(Duration.ofSeconds(5), Duration.ofSeconds(5).minusNanos(1))) {
                server.servedAt = clock.instant().minus(behind);
                assertThat(client(server).fetch(Set.of(id)).items().getFirst().freshness()).isEqualTo(SharedPriceDtos.Freshness.FRESH);
            }
            for (var behind : List.of(Duration.ofSeconds(5).plusNanos(1), Duration.ofSeconds(6), Duration.ofDays(8))) {
                server.servedAt = clock.instant().minus(behind);
                assertThatThrownBy(() -> client(server).fetch(Set.of(id))).isInstanceOf(IllegalArgumentException.class);
            }
        }
    }

    @Test void displayAndCachedFreshnessAdvanceFromValidatedServerTimeAcrossPolicyBoundaries() throws Exception {
        String id = snapshot.products().keySet().iterator().next();
        var localClock = new SharedPriceAdapterTests.MutableClock(clock.instant().minusSeconds(5));
        var product = view(snapshot.products().get(id).identity());
        var counts = product.type() == PartType.RAM
                ? Map.of(product.id(), snapshot.products().get(id).identity().moduleCount()) : Map.<String, Integer>of();
        try (var server = new FullServer()) {
            server.change.set(items -> List.of(new SharedPriceDtos.Item(id, items.getFirst().product(),
                    new CatalogProductView.CurrentPrice(100, "DANAWA", "https://prod.danawa.com/info/?pcode=123", clock.instant()),
                    SharedPriceDtos.Status.OK, SharedPriceDtos.Freshness.FRESH)));
            var localClient = new SharedPriceClient(server.origin, Duration.ofSeconds(2), 65536, snapshot, localClock, policy);
            var adapter = new SharedPriceAdapter(snapshot, localClient, localClock, policy, mock(RamSpecRepository.class));
            var fresh = adapter.enrich(List.of(product), counts).getFirst();
            assertThat(fresh.priceStatus().freshness()).isEqualTo("FRESH");
            assertThat(fresh.priceStatus().includedInTotal()).isTrue();
            Instant receivedAt = localClock.now;
            server.failAllRequests = true;
            for (var age : List.of(Duration.ofHours(48).minusNanos(1), Duration.ofHours(48), Duration.ofDays(7))) {
                localClock.now = receivedAt.plus(age);
                var cached = adapter.enrich(List.of(product), counts).getFirst();
                assertThat(cached.currentPrice().observedAt()).isEqualTo(clock.instant());
                assertThat(cached.priceStatus().lookupStatus()).isEqualTo("UNAVAILABLE");
                assertThat(cached.priceStatus().freshness()).isEqualTo(policy.classify(clock.instant(), clock.instant().plus(age)).name());
                assertThat(cached.priceStatus().includedInTotal()).isFalse();
            }
            localClock.now = receivedAt.minusSeconds(1);
            assertThat(adapter.enrich(List.of(product), counts).getFirst().priceStatus().freshness()).isEqualTo("FRESH");
        }
    }

    private SharedPriceClient client(FullServer server) {
        return new SharedPriceClient(server.origin, Duration.ofSeconds(2), 65536, snapshot, clock, policy);
    }
    private CatalogProductView view(SharedPriceDtos.Identity identity) {
        return new CatalogProductView(UUID.randomUUID().toString(), identity.type(), identity.manufacturer(), identity.modelName(),
                identity.partNumber(), identity.verificationStatus(), identity.active(), clock.instant(), clock.instant(),
                new CatalogProductView.ReferencePrice(null, CatalogPriceStatus.UNCONFIRMED, clock.instant()),
                new CatalogProductView.CurrentPrice(1, "LOCAL", "https://example.org", clock.instant()), identity.canonicalId(),
                null, identity.identityKind(), identity.role());
    }
    private static String resource(String name) throws Exception {
        try (var input = SharedFullCatalogTests.class.getResourceAsStream("/catalog/shared/full/" + name)) {
            return new String(java.util.Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private final class FullServer implements AutoCloseable {
        private final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final URI origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        final AtomicInteger requests = new AtomicInteger();
        final AtomicReference<UnaryOperator<List<SharedPriceDtos.Item>>> change = new AtomicReference<>(items -> items);
        final List<Integer> requestSizes = new ArrayList<>(), responseSizes = new ArrayList<>();
        final List<String> paths = new ArrayList<>();
        int failedRequest; boolean failAllRequests; Instant servedAt; String catalogVersion = snapshot.version();
        FullServer() throws Exception {
            server.createContext("/", exchange -> {
                int number = requests.incrementAndGet(); paths.add(exchange.getRequestURI().getRawPath());
                var ids = List.of(exchange.getRequestURI().getRawQuery().substring("canonicalIds=".length()).split(","));
                requestSizes.add(ids.size());
                Instant now = servedAt == null ? clock.instant() : servedAt;
                var items = ids.stream().map(id -> {
                    var entry = snapshot.products().get(id); var quote = entry.price();
                    return new SharedPriceDtos.Item(id, entry.identity(), quote,
                            quote == null ? SharedPriceDtos.Status.NO_PRICE : SharedPriceDtos.Status.OK,
                            policy.classify(quote == null ? null : quote.observedAt(), now));
                }).toList();
                byte[] body = MAPPER.writeValueAsBytes(new SharedPriceDtos.EnvelopeV2(2, catalogVersion,
                        "prices-v1-" + "f".repeat(64), now, policy.toView(), change.get().apply(items)));
                responseSizes.add(body.length);
                try {
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(failAllRequests || number == failedRequest ? 503 : 200, body.length);
                    exchange.getResponseBody().write(body);
                } finally { exchange.close(); }
            });
            server.start();
        }
        public void close() { server.stop(0); }
    }
}
