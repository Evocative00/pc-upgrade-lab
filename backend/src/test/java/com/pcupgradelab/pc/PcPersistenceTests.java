package com.pcupgradelab.pc;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

/** H2에서 PC/부품 저장 규칙을 확인한다. 실제 MySQL 검사는 src/mysqlTest에 따로 있다. */
@SpringBootTest
@ActiveProfiles("test")
class PcPersistenceTests {
    @Autowired PcConfigurationRepository repository;
    @Autowired TransactionTemplate transactions;
    @Autowired JdbcTemplate jdbc;

    // 여러 부품과 미연결 원문을 저장한 뒤 같은 PC를 수정해, 이전 부품 행이 중복으로 남지 않는지 확인한다.
    @Test
    void preservesMultipleDevicesUnknownCatalogAndReplacesWithoutDuplication() {
        var id = transactions.execute(status -> repository.saveAndFlush(new PcConfiguration(
                "test-owner", "처음 PC", List.of(part(PartType.RAM, "DIMM A"), part(PartType.RAM, "DIMM B"),
                part(PartType.STORAGE, "SSD A"), part(PartType.STORAGE, "SSD B")))).getId());

        transactions.executeWithoutResult(status -> {
            var pc = repository.findByIdAndOwnerKey(id, "test-owner").orElseThrow();
            assertThat(pc.getParts()).hasSize(4);
            assertThat(pc.getParts().getFirst().toInput().rawName()).isEqualTo("unmatched original name");
            assertThat(pc.getParts().getFirst().toInput().catalogProductId()).isNull();
            assertThat(pc.getParts().getFirst().toInput().specs().get("capacityBytes").toString()).isEqualTo("17179869184");
            assertThat(repository.findByIdAndOwnerKey(id, "different-owner")).isEmpty();
            pc.update("바뀐 PC", List.of(part(PartType.RAM, "DIMM C"), part(PartType.STORAGE, "SSD C")));
        });
        transactions.executeWithoutResult(status -> {
            var pc = repository.findByIdAndOwnerKey(id, "test-owner").orElseThrow();
            assertThat(pc.getId()).isEqualTo(id);
            assertThat(pc.getName()).isEqualTo("바뀐 PC");
            assertThat(pc.getParts()).hasSize(2);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pc_part WHERE pc_id = ?", Integer.class, id)).isEqualTo(2);
            repository.delete(pc);
        });
    }

    // 잘못된 교체 요청은 기존 이름과 부품을 변경하기 전에 거절해야 한다.
    @Test
    void rejectsInvalidReplacementBeforeChangingSavedAggregate() {
        var pc = new PcConfiguration("owner", "PC", List.of(part(PartType.RAM, "Original")));
        var invalid = new PartInput(PartType.RAM, "Bad", null, 0, InputSource.MANUAL, null, MatchStatus.UNMATCHED, Map.of());
        assertThatThrownBy(() -> pc.update("New name", List.of(invalid))).isInstanceOf(IllegalArgumentException.class);
        assertThat(pc.getName()).isEqualTo("PC");
        assertThat(pc.getParts().getFirst().toInput().displayName()).isEqualTo("Original");
    }

    private PartInput part(PartType type, String name) {
        return new PartInput(type, name, "unmatched original name", 1, InputSource.AUTO,
                null, MatchStatus.UNMATCHED, Map.of("capacityBytes", 17179869184L));
    }
}
