package com.pcupgradelab.catalog.shared;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AllCatalogEvidenceTimestampReaderTests {
    @Test void legacySessionWallClockReproducesTheOriginalUtcBindingFieldsWithoutAnyOffsetAllowance() {
        assertThat(AllCatalogEvidenceTimestampReader.legacyWallClock("2026-10-01 05:49:27.452000"))
                .isEqualTo(Instant.parse("2026-10-01T05:49:27.452Z"));
        assertThat(AllCatalogEvidenceTimestampReader.legacyWallClock("2026-09-30 20:49:27.452000"))
                .isNotEqualTo(Instant.parse("2026-10-01T05:49:27.452Z"));
    }
    @Test void pilotUtcEpochPreservesMicrosecondsWithoutSessionTimeOrRounding() {
        Instant expected = Instant.parse("2026-10-01T05:49:27.452123Z");
        var epoch = BigDecimal.valueOf(expected.getEpochSecond()).add(BigDecimal.valueOf(expected.getNano(),9));
        assertThat(AllCatalogEvidenceTimestampReader.utcEpoch(epoch)).isEqualTo(expected);
        assertThat(AllCatalogEvidenceTimestampReader.utcEpoch(epoch.subtract(BigDecimal.valueOf(32400)))).isNotEqualTo(expected);
        assertThatThrownBy(() -> AllCatalogEvidenceTimestampReader.utcEpoch(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
