package com.pcupgradelab.pricetrend;

import com.pcupgradelab.catalog.*;
import com.pcupgradelab.catalog.price.CatalogPriceImportBatch;
import com.pcupgradelab.catalog.price.CatalogPriceImportLoader;
import com.pcupgradelab.catalog.price.CatalogPriceImportService;
import com.pcupgradelab.pc.PartType;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 전용 H2에서 가져오기·분석·API가 기존 카탈로그·가격 테이블을 바꾸지 않는지 검사한다.
 * 실제 MySQL 적용 검증은 아니다.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:price-trend-test;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver", "catalog.seed.enabled=false"
})
@ActiveProfiles({"local", "test"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PriceTrendIntegrationTests {
    private static final Instant REVIEWED = Instant.parse("2026-10-01T00:00:00Z");
    /** 기존 카탈로그·가격 테이블. 가격 판단 기능은 이 테이블에 쓰지 않아야 한다. */
    private static final List<String> EXISTING_TABLES = List.of("catalog_product", "catalog_product_source",
            "cpu_spec", "ram_spec", "catalog_reference_price", "catalog_price_mapping", "catalog_price_observation");

    @Autowired CatalogEntryService entries;
    @Autowired CatalogPriceImportService catalogPrices;
    @Autowired PriceHistoryImportService historyImport;
    @Autowired PriceTrendAnalysisService analysis;
    @Autowired PriceInsightService insights;
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        try (var connection = jdbc.getDataSource().getConnection()) {
            assertThat(connection.getMetaData().getURL()).startsWith("jdbc:h2:mem:price-trend-test");
        }
        assertThat(count("catalog_product")).isZero();
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @AfterEach
    void cleanup() {
        for (String table : List.of("price_analysis_result", "price_history_observation", "catalog_price_observation",
                "catalog_price_mapping", "catalog_product_source", "cpu_spec", "ram_spec",
                "catalog_reference_price", "catalog_product")) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    @Test
    void importAnalysisAndApiNeverChangeExistingCatalogOrPriceTables() throws Exception {
        var cpu = fixture(PartType.CPU, "A");
        // 기존 가져오기로 최신 관측 1건을 기존 테이블에 둔다.
        catalogPrices.apply(new CatalogPriceImportBatch(1, List.of(item(cpu, "100", 399_000, "2026-10-10T08:10:24.509Z"))));
        var before = snapshot();

        var files = List.of(new PriceHistoryImportService.NamedBatch("data/price-history/observations-test.json",
                new CatalogPriceImportBatch(1, List.of(
                        item(cpu, "100", 399_000, "2026-10-08T10:26:57.700Z"),
                        item(cpu, "100", 399_000, "2026-10-09T16:28:00.260Z"),
                        // 기존 테이블에 있는 관측은 이력에 다시 넣지 않는다.
                        item(cpu, "100", 399_000, "2026-10-10T08:10:24.509Z"),
                        // 파일 안 중복은 한 건으로 센다.
                        item(cpu, "100", 399_000, "2026-10-09T16:28:00.260Z"),
                        // 카탈로그에 없는 상품은 만들지 않고 건너뛴다.
                        item(unknownProduct(), "200", 10_000, "2026-10-09T00:00:00Z"),
                        // 모델명이 다른 상품은 연결하지 않는다.
                        item(new CatalogPriceImportBatch.Product(CatalogSourceName.BUILDCORES, cpu.externalId(),
                                "Maker", "Different model", cpu.partNumber()), "300", 10_000, "2026-10-09T00:00:00Z")))));

        var preview = historyImport.preview(files);
        assertThat(preview).isEqualTo(new PriceHistoryImportService.Result(true, 6, 2, 1, 0, 1, 1, 1, preview.skipped()));
        assertThat(count("price_history_observation")).isZero();

        var applied = historyImport.apply(files, Instant.parse("2026-11-07T00:00:00Z"));
        assertThat(applied.created()).isEqualTo(2);
        assertThat(count("price_history_observation")).isEqualTo(2);
        // 재실행은 중복을 만들지 않는다.
        var again = historyImport.apply(files, Instant.parse("2026-11-07T01:00:00Z"));
        assertThat(again.created()).isZero();
        assertThat(again.alreadyInHistory()).isEqualTo(2);
        assertThat(count("price_history_observation")).isEqualTo(2);

        // 기존 관측과 새 이력을 합쳐 3일치로 읽는다.
        String productId = jdbc.queryForObject("SELECT id FROM catalog_product", String.class);
        var insight = insights.insight(productId, Instant.parse("2026-10-11T00:00:00Z"));
        assertThat(insight.basis()).isEqualTo("REGISTERED_OBSERVATIONS");
        assertThat(insight.observedDayCount()).isEqualTo(3);
        assertThat(insight.sampleCount()).isEqualTo(3);
        assertThat(insight.verdict()).isEqualTo(PriceTrendAnalyzer.Verdict.FAIR);
        assertThat(insight.reason()).isEqualTo(PriceTrendAnalyzer.Reason.WITHIN_RANGE);
        assertThat(insight.lowConfidence()).isTrue();
        assertThat(insight.stale()).isFalse();

        mvc.perform(get("/api/catalog/products/{id}/price-history", productId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.series.length()").value(1))
                .andExpect(jsonPath("$.series[0].externalId").value("100"))
                .andExpect(jsonPath("$.series[0].points.length()").value(3))
                .andExpect(jsonPath("$.series[0].points[0].observedAt").value("2026-10-08T10:26:57.700Z"))
                .andExpect(jsonPath("$.series[0].points[0].origin").value("HISTORY"))
                .andExpect(jsonPath("$.series[0].points[2].origin").value("CATALOG"));
        mvc.perform(get("/api/catalog/products/{id}/price-insight", productId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verdict").value("FAIR"))
                .andExpect(jsonPath("$.reason").value("WITHIN_RANGE"))
                .andExpect(jsonPath("$.stale").value(true))
                .andExpect(jsonPath("$.lowConfidence").value(true))
                .andExpect(jsonPath("$.windowEnd").value("2026-10-10T08:10:24.509Z"))
                .andExpect(jsonPath("$.latest.amountKrw").value(399_000))
                .andExpect(jsonPath("$.minComparableDays").value(PriceTrendAnalyzer.MIN_COMPARABLE_DAYS))
                .andExpect(jsonPath("$.minObservedDays").value(PriceTrendAnalyzer.MIN_OBSERVED_DAYS))
                .andExpect(jsonPath("$.staleAfterDays").value(7));
        mvc.perform(get("/api/catalog/products/{id}/price-insight", "missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CATALOG_PRODUCT_NOT_FOUND"));

        var analysisPreview = analysis.preview(Instant.parse("2026-10-11T00:00:00Z"));
        assertThat(analysisPreview.products()).isEqualTo(1);
        assertThat(count("price_analysis_result")).isZero();
        var stored = analysis.apply(Instant.parse("2026-10-11T00:00:00Z"));
        assertThat(stored.verdicts().get(PriceTrendAnalyzer.Verdict.FAIR)).isEqualTo(1);
        assertThat(count("price_analysis_result")).isEqualTo(1);

        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void conflictingAmountAtTheSameOfferAndTimeRefusesTheWholeRun() {
        var cpu = fixture(PartType.CPU, "B");
        var files = List.of(new PriceHistoryImportService.NamedBatch("conflict.json", new CatalogPriceImportBatch(1, List.of(
                item(cpu, "500", 100_000, "2026-10-08T00:00:00Z"),
                item(cpu, "501", 100_000, "2026-10-08T00:00:00Z"),
                item(cpu, "500", 120_000, "2026-10-08T00:00:00Z")))));
        assertThatThrownBy(() -> historyImport.apply(files, Instant.parse("2026-11-07T00:00:00Z")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(count("price_history_observation")).isZero();
    }

    @Test
    void offerAlreadyMappedToAnotherProductIsSkipped() {
        var a = fixture(PartType.CPU, "C");
        var b = fixture(PartType.CPU, "D");
        catalogPrices.apply(new CatalogPriceImportBatch(1, List.of(item(a, "700", 100_000, "2026-10-08T00:00:00Z"))));
        var result = historyImport.preview(List.of(new PriceHistoryImportService.NamedBatch("other.json",
                new CatalogPriceImportBatch(1, List.of(item(b, "700", 100_000, "2026-10-09T00:00:00Z"))))));
        assertThat(result.skippedMismatch()).isEqualTo(1);
        assertThat(result.created()).isZero();
        assertThat(result.skipped().getFirst()).startsWith("OFFER_MAPPED_TO_OTHER_PRODUCT");
    }

    @Test
    void existingCatalogOfferCannotChangeSkuOrUrl() {
        var cpu = fixture(PartType.CPU, "F");
        var original = item(cpu, "910", 100_000, "2026-10-08T00:00:00Z");
        catalogPrices.apply(new CatalogPriceImportBatch(1, List.of(original)));
        var later = item(cpu, "910", 50_000, "2026-10-09T00:00:00Z");
        var files = batches(withOffer(later, original.offer().sourceUrl(), "Different SKU"),
                withOffer(later, "https://example.com/different-offer", original.offer().saleSku()));

        var result = historyImport.apply(files, Instant.parse("2026-11-07T00:00:00Z"));

        assertThat(result.created()).isZero();
        assertThat(result.skippedMismatch()).isEqualTo(2);
        assertThat(result.skipped()).allMatch(line -> line.startsWith("OFFER_CONFIGURATION_MISMATCH"));
        assertThat(count("price_history_observation")).isZero();
    }

    @Test
    void existingCatalogObservationConflictsAreNotSilentlySkipped() {
        var cpu = fixture(PartType.CPU, "G");
        var original = item(cpu, "920", 100_000, "2026-10-08T00:00:00Z");
        catalogPrices.apply(new CatalogPriceImportBatch(1, List.of(original)));
        var before = snapshot();
        var unrelatedNewObservation = item(cpu, "920", 99_000, "2026-10-07T00:00:00Z");
        var conflicts = List.of(item(cpu, "920", 90_000, "2026-10-08T00:00:00Z"),
                withEvidence(original, "https://example.com/different-evidence"));

        for (var conflict : conflicts) {
            assertThatThrownBy(() -> historyImport.apply(batches(unrelatedNewObservation, conflict),
                    Instant.parse("2026-11-07T00:00:00Z")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("existing catalog observation differs");
            assertThat(count("price_history_observation")).isZero();
            assertThat(snapshot()).isEqualTo(before);
        }
    }

    @Test
    void historyOnlyOfferRetainsProductAndSkuAcrossSeparateImports() {
        var cpu = fixture(PartType.CPU, "H");
        var otherCpu = fixture(PartType.CPU, "I");
        var original = item(cpu, "930", 100_000, "2026-10-08T00:00:00Z");
        historyImport.apply(batches(original), Instant.parse("2026-11-07T00:00:00Z"));
        var later = item(cpu, "930", 50_000, "2026-10-09T00:00:00Z");
        var conflicts = List.of(item(otherCpu, "930", 50_000, "2026-10-09T00:00:00Z"),
                withOffer(later, original.offer().sourceUrl(), "Different SKU"));

        for (var conflict : conflicts) {
            assertThatThrownBy(() -> historyImport.apply(batches(conflict), Instant.parse("2026-11-07T01:00:00Z")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("existing history offer differs");
            assertThat(count("price_history_observation")).isEqualTo(1);
        }
    }

    @Test
    void identicalAmountButDifferentEvidenceIsNotTheSameObservation() {
        var cpu = fixture(PartType.CPU, "J");
        var original = item(cpu, "940", 100_000, "2026-10-08T00:00:00Z");
        var differentEvidence = withEvidence(original, "https://example.com/different-evidence");
        var importedAt = Instant.parse("2026-11-07T00:00:00Z");

        assertThatThrownBy(() -> historyImport.apply(batches(original, differentEvidence), importedAt))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Conflicting price or evidence");
        assertThat(count("price_history_observation")).isZero();

        historyImport.apply(batches(original), importedAt);
        assertThatThrownBy(() -> historyImport.apply(batches(differentEvidence), importedAt))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("imported history row differs");
        assertThat(count("price_history_observation")).isEqualTo(1);
    }

    @Test
    void deletingACatalogProductIsNotBlockedByHistoryRows() {
        var cpu = fixture(PartType.CPU, "E");
        historyImport.apply(List.of(new PriceHistoryImportService.NamedBatch("cascade.json",
                new CatalogPriceImportBatch(1, List.of(item(cpu, "900", 100_000, "2026-10-08T00:00:00Z"))))),
                Instant.parse("2026-11-07T00:00:00Z"));
        String productId = jdbc.queryForObject("SELECT id FROM catalog_product", String.class);
        jdbc.update("INSERT INTO price_analysis_result (product_id, analyzed_at, method_version, window_start, window_end, "
                + "sample_count, observed_day_count, verdict, reason_code) VALUES (?, CURRENT_TIMESTAMP, 'trend-v1', "
                + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 0, 'INSUFFICIENT_DATA', 'NO_OBSERVATION')", productId);
        jdbc.update("DELETE FROM catalog_product_source");
        jdbc.update("DELETE FROM cpu_spec");
        jdbc.update("DELETE FROM catalog_reference_price");
        jdbc.update("DELETE FROM catalog_product");
        assertThat(count("price_history_observation")).isZero();
        assertThat(count("price_analysis_result")).isZero();
    }

    @Test
    void repositoryApprovedFilesParseWithoutDatabase() {
        var batches = PriceTrendApplication.loadFiles(Path.of("..").toAbsolutePath().normalize(), new CatalogPriceImportLoader());
        var keys = new HashSet<PricePoint.ObservationKey>();
        batches.forEach(batch -> batch.batch().items().forEach(item -> keys.add(new PricePoint.ObservationKey(
                item.offer().sourceName(), item.offer().externalId(), item.price().observedAt()))));
        assertThat(batches).hasSizeGreaterThanOrEqualTo(PriceTrendApplication.APPROVED_FILES.size());
        // 승인 파일 5개의 고유 관측. 파워·케이스 2건은 기존 관측 테이블로 읽는다.
        assertThat(keys).hasSizeGreaterThanOrEqualTo(159);
    }

    private Map<String, List<Map<String, Object>>> snapshot() {
        var tables = new LinkedHashMap<String, List<Map<String, Object>>>();
        for (String table : EXISTING_TABLES) {
            var rows = new ArrayList<Map<String, Object>>();
            for (var row : jdbc.queryForList("SELECT * FROM " + table + " ORDER BY 1")) {
                var copy = new LinkedHashMap<String, Object>();
                // byte[](원본 JSON)는 내용으로 비교한다.
                row.forEach((column, value) -> copy.put(column,
                        value instanceof byte[] bytes ? Base64.getEncoder().encodeToString(bytes) : value));
                rows.add(copy);
            }
            tables.put(table, rows);
        }
        return tables;
    }

    private CatalogPriceImportBatch.Product fixture(PartType type, String suffix) {
        String externalId = UUID.randomUUID().toString();
        CatalogSpecification specification = type == PartType.CPU
                ? new CatalogSpecification.Cpu(null, null, null, null, null, null, null, null)
                : new CatalogSpecification.Ram("DDR5", 16L * 1024 * 1024 * 1024, 2, 6000, "DIMM", null, null, null,
                        new BigDecimal("1.35"), null);
        entries.create(new CatalogEntryCreateRequest(new CatalogProductCreateRequest(type, "Maker", "Model " + suffix,
                "SKU-" + suffix), specification, List.of(new CatalogSourceInput(CatalogSourceName.BUILDCORES,
                externalId, "a".repeat(40), "https://example.com/source", Map.of("opendb_id", externalId), REVIEWED))));
        return new CatalogPriceImportBatch.Product(CatalogSourceName.BUILDCORES, externalId, "Maker", "Model " + suffix,
                "SKU-" + suffix);
    }

    private CatalogPriceImportBatch.Product unknownProduct() {
        return new CatalogPriceImportBatch.Product(CatalogSourceName.BUILDCORES, UUID.randomUUID().toString(),
                "Maker", "Unknown", "SKU-X");
    }

    private CatalogPriceImportBatch.Item item(CatalogPriceImportBatch.Product product, String offerId,
                                               long amount, String observedAt) {
        var offer = new CatalogPriceImportBatch.Offer("DANAWA", offerId, "https://example.com/" + offerId,
                "Reviewed " + offerId, CatalogPriceImportBatch.SaleUnit.PRODUCT, null, REVIEWED);
        var price = new CatalogPriceImportBatch.Price(amount, Instant.parse(observedAt),
                "https://example.com/evidence", "NEW", true);
        return new CatalogPriceImportBatch.Item(product, offer, price);
    }

    private static List<PriceHistoryImportService.NamedBatch> batches(CatalogPriceImportBatch.Item... items) {
        return List.of(new PriceHistoryImportService.NamedBatch("data/price-history/observations-test.json",
                new CatalogPriceImportBatch(1, List.of(items))));
    }

    private static CatalogPriceImportBatch.Item withOffer(CatalogPriceImportBatch.Item item, String url, String sku) {
        var offer = item.offer();
        return new CatalogPriceImportBatch.Item(item.product(), new CatalogPriceImportBatch.Offer(offer.sourceName(),
                offer.externalId(), url, sku, offer.saleUnit(), offer.moduleCount(), offer.verifiedAt()), item.price());
    }

    private static CatalogPriceImportBatch.Item withEvidence(CatalogPriceImportBatch.Item item, String url) {
        var price = item.price();
        return new CatalogPriceImportBatch.Item(item.product(), item.offer(), new CatalogPriceImportBatch.Price(
                price.amountKrw(), price.observedAt(), url, price.condition(), price.inStock()));
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
