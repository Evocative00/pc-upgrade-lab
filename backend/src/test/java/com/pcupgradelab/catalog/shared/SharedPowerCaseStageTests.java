package com.pcupgradelab.catalog.shared;

import com.pcupgradelab.catalog.CatalogProductView;
import com.pcupgradelab.catalog.CatalogPriceStatus;
import com.pcupgradelab.catalog.RamSpecRepository;
import com.pcupgradelab.catalog.powercase.PowerCaseCatalogPreviewLoader;
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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class SharedPowerCaseStageTests {
    private static final List<String> INPUTS = List.of(SharedPowerCaseStage.FILE, PowerCaseCatalogPreviewLoader.FILE,
            "data/catalog-shared/all-catalog-identities-2026-10-10.json", SharedCatalogSnapshot.ACTIVE_PRICES_FILE,
            SharedCatalogSnapshot.APPROVED_RETAIL_FILE, RetailCatalogPreviewLoader.FILE,
            SharedReferencePriceSnapshot.FILE, SharedReferencePriceSnapshot.ACTIVE_FILE);
    private final Path repository = Path.of("..").toAbsolutePath().normalize();
    private final JsonMapper mapper = JsonMapper.builder().build();
    @TempDir Path temporary;

    @BeforeEach void publicInputsOnly() throws Exception {
        for (String file : INPUTS) {
            var target = temporary.resolve(file);
            Files.createDirectories(target.getParent());
            Files.copy(repository.resolve(file), target);
        }
    }

    @Test void exactUnpublishedStagePreservesAllApprovedPricesAnd227References() {
        var base = SharedCatalogSnapshot.loadApprovedRetail(temporary);
        var references = SharedReferencePriceSnapshot.load(temporary, base);
        var stage = SharedPowerCaseStage.load(temporary);
        assertThat(stage.catalog().products()).hasSize(315).containsAllEntriesOf(base.products());
        assertThat(stage.catalog().products().values().stream().filter(p -> p.price() != null)).hasSize(83);
        assertThat(stage.catalog().version()).isNotEqualTo(base.version());
        assertThat(stage.catalog().priceVersion()).isNotEqualTo(base.priceVersion());
        assertThat(stage.envelope().additions()).hasSize(7).allSatisfy(p -> {
            assertThat(p.entry().identity().active()).isFalse();
            assertThat(p.officialEvidenceUrls()).isNotEmpty();
        });
        assertThat(stage.references().products()).containsExactlyElementsOf(references.products()).hasSize(227);
        assertThat(stage.references().referenceVersion()).isEqualTo(references.referenceVersion());
        assertThat(stage.references().generatedAt()).isEqualTo(references.generatedAt());
        assertThat(stage.references().catalogVersion()).isEqualTo(stage.catalog().version());
        assertThat(stage.references().publicationApproved()).isFalse();
        assertThat(stage.envelope().publicationApproved()).isFalse();
        assertThat(stage.envelope().databaseApplyApproved()).isFalse();
        assertThat(stage.envelope().additions().stream().filter(p -> p.entry().price() != null)
                .map(p -> p.entry().price().amountKrw())).containsExactly(75600L, 149000L);
        assertThat(SharedCatalogSnapshot.loadApprovedRetail(temporary).products()).hasSize(308).isEqualTo(base.products());
    }

    @Test void alteredEvidenceOrApprovalCannotBecomeAStageEvenWithValidOrdinaryJson() throws Exception {
        var review = (ObjectNode) mapper.readTree(Files.readString(temporary.resolve(PowerCaseCatalogPreviewLoader.FILE)));
        ((ObjectNode) review.get("products").get(0)).put("modelName", "MAG A650BN altered");
        Files.writeString(temporary.resolve(PowerCaseCatalogPreviewLoader.FILE), mapper.writeValueAsString(review));
        assertThatThrownBy(() -> SharedPowerCaseStage.load(temporary)).isInstanceOf(IllegalArgumentException.class);
        Files.copy(repository.resolve(PowerCaseCatalogPreviewLoader.FILE), temporary.resolve(PowerCaseCatalogPreviewLoader.FILE),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        var manifest = (ObjectNode) mapper.readTree(Files.readString(temporary.resolve(SharedPowerCaseStage.FILE)));
        manifest.put("publicationApproved", true);
        Files.writeString(temporary.resolve(SharedPowerCaseStage.FILE), mapper.writeValueAsString(manifest));
        assertThatThrownBy(() -> SharedPowerCaseStage.load(temporary)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unpublished");
    }

    @Test void callerCannotSupplyExtraPricesOrRepurposeANonMatchingPlan() {
        var plan = new PowerCaseCatalogPreviewLoader().load(temporary);
        var altered = new PowerCaseCatalogPreviewLoader.Plan(plan.review(), plan.registrations(), List.of(),
                plan.centralExtension(), plan.report());
        assertThatThrownBy(() -> SharedPowerCaseStage.fromPlan(temporary, altered)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pinned review");
    }

    @Test void stageExportIsFileOnlyAndLeavesProductionArtifactsAndInputsUnchanged() throws Exception {
        var originals = new LinkedHashMap<String, byte[]>();
        for (String file : INPUTS) originals.put(file, Files.readAllBytes(temporary.resolve(file)));
        PowerCaseSharedStageApplication.main(new String[]{"--source-root", temporary.toString()});
        var exported = mapper.readTree(Files.readString(temporary.resolve(PowerCaseSharedStageApplication.OUTPUT + "/power-case-stage.json")));
        assertThat(exported.get("catalog").get("products").size()).isEqualTo(315);
        assertThat(exported.get("references").get("products").size()).isEqualTo(227);
        assertThat(exported.get("publicationApproved").booleanValue()).isFalse();
        for (var file : originals.entrySet()) assertThat(Files.readAllBytes(temporary.resolve(file.getKey()))).isEqualTo(file.getValue());
        assertThat(Files.exists(temporary.resolve("workers/catalog-prices/generated"))).isFalse();
        assertThatThrownBy(() -> PowerCaseSharedStageApplication.main(new String[]{"--apply", temporary.toString()}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void onlyExplicitLoopbackAdapterRequestsAllSevenNewIdentitiesAndPreservesReferenceResponses() throws Exception {
        var stage = SharedPowerCaseStage.load(temporary);
        var now = stage.catalog().products().values().stream().filter(p -> p.price() != null)
                .map(p -> p.price().observedAt()).max(Instant::compareTo).orElseThrow().plusSeconds(3600);
        var clock = Clock.fixed(now, ZoneOffset.UTC);
        var calls = new AtomicInteger();
        var changeCurrent = new AtomicBoolean();
        var changeReferenceVersion = new AtomicBoolean();
        var http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/", exchange -> {
            try {
                calls.incrementAndGet();
                assertThat(exchange.getRequestHeaders().getFirst("X-Catalog-Version")).isEqualTo(stage.catalog().version());
                assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer " + "1".repeat(64));
                var ids = Arrays.asList(exchange.getRequestURI().getRawQuery().substring("canonicalIds=".length()).split(","));
                Object envelope;
                if (exchange.getRequestURI().getPath().equals(SharedReferencePriceClient.API_PATH)) {
                    var byId = new LinkedHashMap<String, SharedReferencePriceDtos.Product>();
                    for (var reference : stage.references().products()) byId.put(reference.identity().canonicalId(), reference);
                    envelope = new SharedReferencePriceDtos.Envelope(1, stage.catalog().version(), changeReferenceVersion.get()
                            ? "references-v1-" + "0".repeat(64) : stage.references().referenceVersion(), now,
                            ids.stream().map(id -> new SharedReferencePriceDtos.Item(id, stage.catalog().products().get(id).identity(),
                                    byId.containsKey(id) ? byId.get(id).referenceEstimate() : null,
                                    byId.containsKey(id) ? SharedReferencePriceDtos.Status.OK : SharedReferencePriceDtos.Status.NO_REFERENCE)).toList());
                } else {
                    envelope = new SharedPriceDtos.EnvelopeV2(2, stage.catalog().version(), stage.catalog().priceVersion(), now,
                            SharedPriceFreshness.defaults().toView(), ids.stream().map(id -> {
                        var entry = stage.catalog().products().get(id);
                        var price = entry.price();
                        if (price != null && changeCurrent.get()) price = new CatalogProductView.CurrentPrice(price.amountKrw() + 1,
                                price.sourceName(), price.sourceUrl(), price.observedAt());
                        return new SharedPriceDtos.Item(id, entry.identity(), price, entry.price() == null ? SharedPriceDtos.Status.NO_PRICE
                                : SharedPriceDtos.Status.OK, entry.price() == null ? SharedPriceDtos.Freshness.NO_PRICE : SharedPriceDtos.Freshness.FRESH);
                    }).toList());
                }
                byte[] body = mapper.writeValueAsBytes(envelope);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body);
            } finally { exchange.close(); }
        });
        http.start();
        try {
            var origin = URI.create("http://127.0.0.1:" + http.getAddress().getPort());
            var products = new ArrayList<CatalogProductView>();
            for (var addition : stage.envelope().additions()) products.add(view(addition.entry().identity(), now));
            var reference = stage.references().products().getFirst();
            products.add(view(reference.identity(), now));
            var ram = mock(RamSpecRepository.class);
            var counts = reference.identity().moduleCount() == null ? Map.<String, Integer>of()
                    : Map.of(products.getLast().id(), reference.identity().moduleCount());
            var active = SharedCatalogSnapshot.loadApprovedRetail(temporary);
            var baselineAdapter = new SharedPriceAdapter(active, new SharedPriceClient(origin, Duration.ofSeconds(2), 65536,
                    active, clock, SharedPriceFreshness.defaults()), clock, SharedPriceFreshness.defaults(), ram);
            assertThat(baselineAdapter.enrich(products.subList(0, 7), Map.of())).allSatisfy(p ->
                    assertThat(p.priceStatus().lookupStatus()).isEqualTo("NOT_IN_SCOPE"));
            assertThat(calls).hasValue(0);
            var enriched = SharedPriceAdapter.powerCaseStagedPreview(stage, origin, clock, ram).enrich(products, counts);
            assertThat(enriched).hasSize(8).allSatisfy(p -> assertThat(p.priceStatus().origin()).isEqualTo("SHARED"));
            assertThat(enriched.subList(0, 7).stream().filter(p -> p.currentPrice() != null)).hasSize(2);
            assertThat(enriched.subList(0, 7)).allSatisfy(p -> assertThat(p.referenceEstimate()).isNull());
            assertThat(enriched.getLast().referenceEstimate()).isEqualTo(reference.referenceEstimate());
            assertThat(calls).hasValue(2);
            changeCurrent.set(true);
            var alteredCurrent = SharedPriceAdapter.powerCaseStagedPreview(stage, origin, clock, ram).enrich(products, counts);
            assertThat(alteredCurrent).allSatisfy(p -> {
                assertThat(p.currentPrice()).isNull();
                assertThat(p.priceStatus().lookupStatus()).isEqualTo("UNAVAILABLE");
            });
            changeCurrent.set(false); changeReferenceVersion.set(true);
            var alteredReferences = SharedPriceAdapter.powerCaseStagedPreview(stage, origin, clock, ram).enrich(products, counts);
            assertThat(alteredReferences.getLast().referenceEstimate()).isNull();
            assertThat(alteredReferences.subList(0, 7).stream().filter(p -> p.currentPrice() != null)).hasSize(2);
            assertThatThrownBy(() -> SharedPriceAdapter.powerCaseStagedPreview(stage, URI.create("https://example.com"), clock, ram))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("loopback");
        } finally { http.stop(0); }
    }

    private static CatalogProductView view(SharedPriceDtos.Identity identity, Instant now) {
        return new CatalogProductView("stage-private-" + identity.canonicalId(), identity.type(), identity.manufacturer(), identity.modelName(),
                identity.partNumber(), identity.verificationStatus(), identity.active(), now, now,
                new CatalogProductView.ReferencePrice(null, CatalogPriceStatus.UNCONFIRMED, now), null,
                identity.canonicalId(), null, identity.identityKind(), identity.role(), null);
    }
}
