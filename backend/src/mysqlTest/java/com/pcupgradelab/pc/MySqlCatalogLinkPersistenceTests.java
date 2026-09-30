package com.pcupgradelab.pc;

import com.pcupgradelab.BackendApplication;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * mysqlTest 전용 검사. V6까지 적용한 로컬 MySQL과 CPU/보드/RAM/GPU/모니터 초기 자료가 필요하다.
 * 실제 PcService로 저장·수정한 뒤 연결 풀과 Spring 컨텍스트를 닫고 새 컨텍스트에서 재조회한다.
 * 카탈로그는 읽기만 하며 이번 검사에서 만든 PC 한 건만 정리한다. HTTP·브라우저 검사는 별도다.
 */
class MySqlCatalogLinkPersistenceTests {
    private final String pcName = "mysql-catalog-check-" + UUID.randomUUID();
    private final JsonMapper mapper = JsonMapper.builder().build();
    private Long pcId;

    @Test
    void keepsRealCatalogLinksAndDeviceValuesAfterUpdateAndContextRestart() throws Exception {
        List<PartInput> expected;
        try (var app = application()) {
            try (var connection = app.getBean(DataSource.class).getConnection()) {
                assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("MySQL");
            }
            var jdbc = app.getBean(JdbcTemplate.class);
            var service = app.getBean(PcService.class);
            var products = new EnumMap<PartType, CatalogFixture>(PartType.class);
            for (var type : List.of(PartType.CPU, PartType.MOTHERBOARD, PartType.RAM, PartType.GPU, PartType.MONITOR)) {
                products.put(type, findExistingProduct(jdbc, type));
            }

            var ram = linked(PartType.RAM, products.get(PartType.RAM), "DIMM 1");
            var gpu = linked(PartType.GPU, products.get(PartType.GPU), "GPU 1");
            var monitor = linked(PartType.MONITOR, products.get(PartType.MONITOR), "Display 1");
            var initial = List.of(linked(PartType.CPU, products.get(PartType.CPU), "CPU 1"),
                    linked(PartType.MOTHERBOARD, products.get(PartType.MOTHERBOARD), "Board 1"),
                    ram, linked(PartType.RAM, products.get(PartType.RAM), "DIMM 2"), gpu, monitor,
                    new PartInput(PartType.STORAGE, "직접 입력한 SSD", null, 1, InputSource.MANUAL,
                            null, MatchStatus.UNMATCHED, java.util.Map.of("capacityBytes", 1000000000000L)));
            pcId = service.create(new PcDtos.Request(pcName, initial)).id();
            assertThat(pcId).isNotNull();
            // 서비스 호출마다 트랜잭션이 종료된다. 생성 응답만 보지 않고 다시 DB에서 읽는다.
            assertParts(service.findById(pcId).parts(), initial);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pc_part WHERE pc_id = ?", Long.class, pcId))
                    .isEqualTo(7L);

            expected = List.of(new PartInput(ram.type(), ram.displayName(), ram.rawName(), 2,
                            ram.source(), ram.catalogProductId(), ram.matchStatus(), ram.specs()),
                    new PartInput(gpu.type(), gpu.displayName(), gpu.rawName(), gpu.quantity(),
                            gpu.source(), null, MatchStatus.UNMATCHED, gpu.specs()), monitor);
            var updated = service.update(pcId, new PcDtos.Request(pcName + "-updated", expected));
            assertThat(updated.id()).isEqualTo(pcId);
            assertParts(updated.parts(), expected);
        }

        try (var restarted = application()) {
            var reloaded = restarted.getBean(PcService.class).findById(pcId);
            assertThat(reloaded.id()).isEqualTo(pcId);
            assertThat(reloaded.name()).isEqualTo(pcName + "-updated");
            assertParts(reloaded.parts(), expected);
            var jdbc = restarted.getBean(JdbcTemplate.class);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pc_part WHERE pc_id = ?", Long.class, pcId))
                    .isEqualTo(3L);
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM pc_part WHERE pc_id = ? AND match_status = 'MATCHED' AND catalog_product_id IS NOT NULL
                    """, Long.class, pcId)).isEqualTo(2L);
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM pc_part WHERE pc_id = ? AND type = 'GPU'
                        AND match_status = 'UNMATCHED' AND catalog_product_id IS NULL
                    """, Long.class, pcId)).isEqualTo(1L);
        }
    }

    @AfterEach
    void removeOnlyThisRunsPc() {
        if (pcId == null) return;
        try (var app = application()) {
            var repository = app.getBean(PcConfigurationRepository.class);
            app.getBean(TransactionTemplate.class).executeWithoutResult(status ->
                    repository.findByIdAndOwnerKey(pcId, PcService.DEFAULT_OWNER_KEY).ifPresent(pc -> {
                        assertThat(pc.getName()).isIn(pcName, pcName + "-updated");
                        repository.delete(pc);
                    }));
            var jdbc = app.getBean(JdbcTemplate.class);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pc_configuration WHERE id = ?", Long.class, pcId)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pc_part WHERE pc_id = ?", Long.class, pcId)).isZero();
        }
    }

    private ConfigurableApplicationContext application() {
        // 개인 local 접속 설정을 사용한다. 스키마 변경과 seed 실행은 명시적으로 끈다.
        return new SpringApplicationBuilder(BackendApplication.class)
                .profiles("local")
                .web(WebApplicationType.NONE)
                .run("--spring.main.web-application-type=none",
                        "--spring.main.banner-mode=off",
                        "--spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
                        "--spring.jpa.hibernate.ddl-auto=validate",
                        "--spring.flyway.enabled=false",
                        "--catalog.seed.enabled=false");
    }

    private CatalogFixture findExistingProduct(JdbcTemplate jdbc, PartType type) {
        List<CatalogFixture> found = jdbc.query("""
                SELECT id, model_name FROM catalog_product WHERE type = ? ORDER BY model_name, id LIMIT 1
                """, (rs, row) -> new CatalogFixture(rs.getString("id"), rs.getString("model_name")), type.name());
        assertThat(found).as("%s 초기 자료가 필요합니다. 카탈로그 seed 등록 후 실행하세요.", type).hasSize(1);
        return found.getFirst();
    }

    private PartInput linked(PartType type, CatalogFixture product, String slot) {
        // 검사용 장치 값이다. 카탈로그 제원이 이 원문·장치 수량·미확인 값을 덮어쓰면 실패해야 한다.
        var specs = new LinkedHashMap<String, Object>();
        specs.put("slot", slot);
        specs.put("unconfirmedReading", null);
        if (type == PartType.RAM) specs.put("capacityBytes", 8589934592L);
        if (type == PartType.GPU) specs.put("vramBytes", 12884901888L);
        return new PartInput(type, product.modelName(), "  검증 원문: " + type + "  ", 1,
                InputSource.AUTO, product.id(), MatchStatus.MATCHED, specs);
    }

    private void assertParts(List<PartInput> actual, List<PartInput> expected) {
        JsonNode expectedJson = mapper.readTree(mapper.writeValueAsString(expected));
        JsonNode actualJson = mapper.readTree(mapper.writeValueAsString(actual));
        assertThat(actualJson).isEqualTo(expectedJson);
    }

    private record CatalogFixture(String id, String modelName) { }
}
