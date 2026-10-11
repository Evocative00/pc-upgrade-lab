package com.pcupgradelab.catalog.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class SharedPricePublisherTest {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newHttpClient();

    @Test void bundledApprovalPreservesTheFourteenExactIdentitiesAndEightPrices() {
        var snapshot = SharedCatalogSnapshot.loadPilot();
        assertThat(snapshot.products()).hasSize(14);
        assertThat(snapshot.products().values().stream().filter(entry -> entry.price() != null)).hasSize(8);
        assertThat(snapshot.products().values()).allSatisfy(entry -> {
            assertThat(entry.identity().active()).isFalse();
            assertThat(entry.identity().verificationStatus().name()).isEqualTo("UNVERIFIED");
            assertThat(UUID.fromString(entry.identity().canonicalId()).toString()).isEqualTo(entry.identity().canonicalId());
        });
        var ram = snapshot.products().values().stream().filter(entry -> "F5-6000J3038F16GX2-TZ5N".equals(entry.identity().partNumber())).findFirst().orElseThrow();
        assertThat(ram.identity().saleUnit()).isEqualTo("RAM_KIT");
        assertThat(ram.identity().moduleCount()).isEqualTo(2);
        assertThat(ram.price().amountKrw()).isEqualTo(939000);
        assertThat(snapshot.version()).isEqualTo("pilot-2026-10-10-28cc2bacb534dcc2-3028116ce4cae9ad");
        assertThatThrownBy(() -> snapshot.products().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void changedPublicPriceCannotBePublishedWithTheApprovedVersion(@TempDir Path root) throws Exception {
        var review = Files.createDirectories(root.resolve("data/catalog-review"));
        for (String file : new String[]{"pilot-import-preview-2026-10-10.json", "pilot-approved-prices-2026-10-10.json"}) {
            try (var stream = getClass().getResourceAsStream("/catalog/shared/" + file)) {
                Files.copy(stream, review.resolve(file));
            }
        }
        var priceFile = review.resolve("pilot-approved-prices-2026-10-10.json");
        Files.writeString(priceFile, Files.readString(priceFile).replace("939000", "1"));
        assertThatThrownBy(() -> SharedCatalogSnapshot.loadPilot(root)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("approved fixed snapshot");
    }

    @Test void realHttpReturnsOnlyRequestedPublicIdentitiesAndOriginalObservations() throws Exception {
        var snapshot = SharedCatalogSnapshot.loadPilot();
        var now = latestObservation(snapshot).plusSeconds(3600);
        try (var publisher = SharedPricePublisher.start(snapshot, Clock.fixed(now, ZoneOffset.UTC), SharedPriceFreshness.defaults(), 0)) {
            String query = String.join(",", snapshot.products().keySet());
            var response = get(publisher, "?canonicalIds=" + query);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
            var envelope = mapper.readValue(response.body(), SharedPriceDtos.Envelope.class);
            assertThat(envelope.schemaVersion()).isEqualTo(1);
            assertThat(envelope.catalogVersion()).isEqualTo(snapshot.version());
            assertThat(envelope.servedAt()).isEqualTo(now);
            assertThat(envelope.policy()).isEqualTo(new SharedPriceDtos.Policy(172800, 604800));
            assertThat(envelope.items()).hasSize(14);
            assertThat(envelope.items().stream().filter(item -> item.status() == SharedPriceDtos.Status.OK)).hasSize(8);
            assertThat(envelope.items().stream().filter(item -> item.status() == SharedPriceDtos.Status.NO_PRICE)).hasSize(6);
            for (var item : envelope.items()) {
                var expected = snapshot.products().get(item.canonicalId());
                assertThat(item.product()).isEqualTo(expected.identity());
                assertThat(item.price()).isEqualTo(expected.price());
            }
            assertThat(response.body()).doesNotContain("modelId", "userId", "pcId", "createdAt", "updatedAt");
        }
    }

    @Test void validUnknownUuidIsDistinctFromAnApprovedProductWithoutPrice() throws Exception {
        var snapshot = SharedCatalogSnapshot.loadPilot();
        var missingPriceId = snapshot.products().entrySet().stream().filter(entry -> entry.getValue().price() == null).findFirst().orElseThrow().getKey();
        var unknown = UUID.randomUUID().toString();
        try (var publisher = SharedPricePublisher.start(snapshot, Clock.systemUTC(), SharedPriceFreshness.defaults(), 0)) {
            var response = get(publisher, "?canonicalIds=" + missingPriceId + "," + unknown);
            var items = mapper.readValue(response.body(), SharedPriceDtos.Envelope.class).items();
            assertThat(items.getFirst().status()).isEqualTo(SharedPriceDtos.Status.NO_PRICE);
            assertThat(items.getFirst().product()).isNotNull();
            assertThat(items.getLast().status()).isEqualTo(SharedPriceDtos.Status.UNKNOWN_PRODUCT);
            assertThat(items.getLast().product()).isNull();
            assertThat(items).allSatisfy(item -> {
                assertThat(item.price()).isNull();
                assertThat(item.freshness()).isEqualTo(SharedPriceDtos.Freshness.NO_PRICE);
            });
        }
    }

    @Test void malformedOrDuplicateRequestsAndOtherRoutesAreRejected() throws Exception {
        var snapshot = SharedCatalogSnapshot.loadPilot();
        String id = snapshot.products().keySet().iterator().next();
        try (var publisher = SharedPricePublisher.start(snapshot, Clock.systemUTC(), SharedPriceFreshness.defaults(), 0)) {
            for (String query : new String[]{"", "?canonicalIds=", "?canonicalIds=bad", "?ids=" + id,
                    "?canonicalIds=" + id + "," + id, "?canonicalIds=" + id + "&pcId=x",
                    "?canonicalIds=" + id + "&canonicalIds=" + id,
                    "?canonicalIds=" + String.join(",", java.util.stream.IntStream.range(0, 101).mapToObj(i -> UUID.randomUUID().toString()).toList())}) {
                assertThat(get(publisher, query).statusCode()).isEqualTo(400);
            }
            var post = HttpRequest.newBuilder(publisher.baseUri().resolve(SharedPricePublisher.PATH + "?canonicalIds=" + id))
                    .POST(HttpRequest.BodyPublishers.noBody()).build();
            assertThat(http.send(post, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(405);
            var other = HttpRequest.newBuilder(publisher.baseUri().resolve("/api/pcs")).GET().build();
            assertThat(http.send(other, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(404);
            var suffix = HttpRequest.newBuilder(publisher.baseUri().resolve(SharedPricePublisher.PATH + "/extra")).GET().build();
            assertThat(http.send(suffix, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(404);
        }
    }

    @Test void publicationUsesTheExactFreshnessBoundariesWithoutRewritingObservedAt() throws Exception {
        var snapshot = SharedCatalogSnapshot.loadPilot();
        var selected = snapshot.products().values().stream().filter(entry -> entry.price() != null)
                .max(java.util.Comparator.comparing(entry -> entry.price().observedAt())).orElseThrow();
        var observed = selected.price().observedAt();
        for (var scenario : new Object[][]{{172799L, SharedPriceDtos.Freshness.FRESH},
                {172800L, SharedPriceDtos.Freshness.STALE}, {604799L, SharedPriceDtos.Freshness.STALE},
                {604800L, SharedPriceDtos.Freshness.EXPIRED}}) {
            try (var publisher = SharedPricePublisher.start(snapshot, Clock.fixed(observed.plusSeconds((long) scenario[0]), ZoneOffset.UTC), SharedPriceFreshness.defaults(), 0)) {
                var response = get(publisher, "?canonicalIds=" + selected.identity().canonicalId());
                var item = mapper.readValue(response.body(), SharedPriceDtos.Envelope.class).items().getFirst();
                assertThat(item.freshness()).isEqualTo(scenario[1]);
                assertThat(item.price().observedAt()).isEqualTo(observed);
            }
        }
    }

    @Test void freshnessRejectsFutureTimeAndInvalidPolicy() {
        var policy = SharedPriceFreshness.defaults();
        var now = Instant.parse("2026-10-10T12:00:00Z");
        assertThat(policy.classify(null, now)).isEqualTo(SharedPriceDtos.Freshness.NO_PRICE);
        assertThatThrownBy(() -> policy.classify(now.plusNanos(1), now)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SharedPriceFreshness(Duration.ZERO, Duration.ofDays(7))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SharedPriceFreshness(Duration.ofDays(7), Duration.ofDays(7))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SharedPriceFreshness(Duration.ofMillis(1), Duration.ofDays(7))).isInstanceOf(IllegalArgumentException.class);
    }

    private HttpResponse<String> get(SharedPricePublisher publisher, String query) throws Exception {
        var request = HttpRequest.newBuilder(publisher.baseUri().resolve(SharedPricePublisher.PATH + query)).GET().build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
    private Instant latestObservation(SharedCatalogSnapshot snapshot) {
        return snapshot.products().values().stream().filter(entry -> entry.price() != null).map(entry -> entry.price().observedAt())
                .max(Instant::compareTo).orElseThrow();
    }
}
