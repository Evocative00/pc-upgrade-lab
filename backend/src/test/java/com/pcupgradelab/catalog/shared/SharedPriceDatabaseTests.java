package com.pcupgradelab.catalog.shared;

import com.pcupgradelab.catalog.CatalogEntryCreateRequest;
import com.pcupgradelab.catalog.CatalogEntryService;
import com.pcupgradelab.catalog.CatalogProductService;
import com.pcupgradelab.catalog.CatalogProductSourceRepository;
import com.pcupgradelab.catalog.CatalogProductView;
import com.pcupgradelab.catalog.CatalogQueryService;
import com.pcupgradelab.catalog.identity.CatalogIdentityRequests;
import com.pcupgradelab.catalog.identity.CatalogIdentityService;
import com.pcupgradelab.catalog.pilot.CatalogPilotImportLoader;
import com.pcupgradelab.catalog.price.CatalogCurrentPriceService;
import com.pcupgradelab.catalog.price.CatalogPriceDatabaseTimeZone;
import com.pcupgradelab.pc.PartType;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;

/** 독립 H2 두 DB에 같은 승인 상품을 서로 다른 로컬 UUID로 만들고 실제 HTTP 읽기만 비교한다. */
class SharedPriceDatabaseTests {
    @Test void independentDatabasesUseCanonicalIdsAndNeitherListNorDetailWritesLocalRows() throws Exception {
        var snapshot = SharedCatalogSnapshot.loadActive();
        var plan = new CatalogPilotImportLoader().load(Path.of("..").toAbsolutePath());
        var cpu = plan.parts().stream().filter(p -> p.product().type() == PartType.CPU
                && snapshot.products().get(p.proposedCanonicalId()).price() != null).findFirst().orElseThrow();
        var ram = plan.parts().stream().filter(p -> p.product().type() == PartType.RAM).findFirst().orElseThrow();
        var latest = snapshot.products().values().stream().filter(p -> p.price() != null)
                .map(p -> p.price().observedAt()).max(java.util.Comparator.naturalOrder()).orElseThrow();
        var clock = Clock.fixed(latest.plus(Duration.ofHours(1)), ZoneOffset.UTC);
        try (var publisher = SharedPricePublisher.start(snapshot, clock, SharedPriceFreshness.defaults(), 0);
             var first = context(publisher.baseUri(), clock);
             var second = context(publisher.baseUri(), clock)) {
            var firstCpu = add(first, plan, cpu); var secondCpu = add(second, plan, cpu);
            var firstRam = add(first, plan, ram); var secondRam = add(second, plan, ram);
            assertThat(firstCpu).isNotEqualTo(secondCpu);
            assertThat(firstRam).isNotEqualTo(secondRam);
            var firstBefore = databaseSnapshot(first); var secondBefore = databaseSnapshot(second);
            var a = first.getBean(CatalogQueryService.class); var b = second.getBean(CatalogQueryService.class);
            var firstList = a.search(null, null, 0, 100).items();
            var secondList = b.search(null, null, 0, 100).items();
            assertThat(firstList).hasSize(2); assertThat(secondList).hasSize(2);
            for (var part : List.of(cpu, ram)) {
                var expected = snapshot.products().get(part.proposedCanonicalId());
                var left = firstList.stream().filter(p -> part.proposedCanonicalId().equals(p.canonicalId())).findFirst().orElseThrow();
                var right = secondList.stream().filter(p -> part.proposedCanonicalId().equals(p.canonicalId())).findFirst().orElseThrow();
                assertThat(left.id()).isNotEqualTo(right.id());
                assertThat(left.currentPrice()).isEqualTo(expected.price()).isEqualTo(right.currentPrice());
                assertThat(left.priceStatus()).isEqualTo(right.priceStatus());
                assertThat(left.priceStatus().origin()).isEqualTo("SHARED");
                assertThat(left.priceStatus().catalogVersion()).isEqualTo(snapshot.version());
                assertThat(left.priceStatus().lookupStatus()).isEqualTo(expected.price() == null ? "NO_PRICE" : "OK");
                assertThat(a.findById(left.id()).product()).isEqualTo(left);
                assertThat(b.findById(right.id()).product()).isEqualTo(right);
            }
            assertThat(databaseSnapshot(first)).isEqualTo(firstBefore);
            assertThat(databaseSnapshot(second)).isEqualTo(secondBefore);
            for (var context : List.of(first, second)) {
                var jdbc = context.getBean(JdbcTemplate.class);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM catalog_price_observation", Integer.class)).isZero();
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM catalog_reference_price WHERE status = 'UNCONFIRMED'", Integer.class))
                        .isEqualTo(2);
            }
        }
    }

    private static AnnotationConfigApplicationContext context(URI origin, Clock clock) {
        var context = new AnnotationConfigApplicationContext();
        var properties = new LinkedHashMap<String, Object>();
        properties.put("spring.datasource.url", "jdbc:h2:mem:shared-adapter-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        properties.put("spring.datasource.username", "sa"); properties.put("spring.datasource.password", "");
        properties.put("spring.datasource.driver-class-name", "org.h2.Driver");
        properties.put("spring.jpa.hibernate.ddl-auto", "validate");
        properties.put("spring.jpa.properties.hibernate.jdbc.time_zone", "UTC");
        properties.put("spring.jpa.open-in-view", "false"); properties.put("spring.flyway.enabled", "true");
        properties.put("spring.flyway.locations", "classpath:db/migration");
        properties.put("catalog.shared-prices.enabled", "true"); properties.put("catalog.shared-prices.base-url", origin.toString());
        properties.put("catalog.shared-prices.api-token", ""); // 개인 팀 토큰을 로컬 테스트 제공기로 보내지 않는다.
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("isolated-h2", properties));
        context.registerBean("sharedPriceClock", Clock.class, () -> clock);
        context.register(DatabaseConfiguration.class);
        try { context.refresh(); return context; }
        catch (RuntimeException ex) { context.close(); throw ex; }
    }

    private static String add(AnnotationConfigApplicationContext context, CatalogPilotImportLoader.Plan plan,
                              CatalogPilotImportLoader.Part part) {
        var identities = context.getBean(CatalogIdentityService.class);
        var model = identities.registerModel(plan.models().stream()
                .filter(m -> m.canonicalId().equals(part.modelCanonicalId())).findFirst().orElseThrow());
        var created = context.getBean(CatalogEntryService.class).create(new CatalogEntryCreateRequest(
                part.product(), part.specification(), List.of(part.manufacturerEvidence()))).product();
        var evidence = context.getBean(CatalogProductSourceRepository.class).findBySourceNameAndExternalId(
                part.manufacturerEvidence().sourceName(), part.manufacturerEvidence().externalId()).orElseThrow();
        identities.bindProduct(new CatalogIdentityRequests.ProductBinding(created.id(), part.proposedCanonicalId(),
                model.id(), part.identityKind(), part.role(), evidence.getId(), part.bindingReviewScope()));
        return created.id();
    }

    /** 모든 PUBLIC 테이블의 값까지 비교하여 조회 뒤 변경·관측 INSERT가 없음을 확인한다. */
    private static Map<String, List<String>> databaseSnapshot(AnnotationConfigApplicationContext context) {
        var jdbc = context.getBean(JdbcTemplate.class);
        var result = new LinkedHashMap<String, List<String>>();
        for (String table : jdbc.queryForList("SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'PUBLIC'", String.class)) {
            if (!table.matches("[A-Za-z_][A-Za-z0-9_]*")) throw new AssertionError("Unexpected H2 table name");
            var rows = jdbc.query("SELECT * FROM \"" + table + "\"", (rs, row) -> {
                var values = new StringBuilder();
                for (int column = 1; column <= rs.getMetaData().getColumnCount(); column++)
                    values.append(rs.getString(column)).append('\u0000');
                return values.toString();
            });
            result.put(table, rows.stream().sorted().toList());
        }
        return result;
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EntityScan("com.pcupgradelab")
    @EnableJpaRepositories("com.pcupgradelab.catalog")
    @Import({CatalogProductService.class, CatalogEntryService.class, CatalogQueryService.class,
            CatalogCurrentPriceService.class, CatalogPriceDatabaseTimeZone.class, CatalogIdentityService.class,
            SharedPriceAdapterConfig.class})
    static class DatabaseConfiguration { }
}
