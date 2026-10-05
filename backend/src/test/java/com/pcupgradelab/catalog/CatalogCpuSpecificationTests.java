package com.pcupgradelab.catalog;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Intel 공표 전력·P/E 제원의 의미와 이전 8항목 JSON의 호환성을 검사한다. */
class CatalogCpuSpecificationTests {
    @Test
    void legacyConstructorAndLegacyJsonKeepNewFieldsUnknown() {
        var legacy = new CatalogSpecification.Cpu("AM4", 6, 12, 3600, 4200,
                new BigDecimal("65"), false, null);
        var mapper = JsonMapper.builder()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
        var parsed = mapper.readValue("""
                {"socketCode":"AM4","coreCount":6,"threadCount":12,
                 "baseClockMhz":3600,"boostClockMhz":4200,"tdpW":65,
                 "hasIntegratedGraphics":false,"integratedGraphicsModel":null}
                """, CatalogSpecification.Cpu.class);
        assertThat(parsed).isEqualTo(legacy);
        assertThat(parsed.processorBasePowerW()).isNull();
        assertThat(parsed.maximumTurboPowerW()).isNull();
        assertThat(parsed.performanceCoreCount()).isNull();
        assertThat(parsed.efficientCoreCount()).isNull();
        assertThat(parsed.performanceCoreBaseClockMhz()).isNull();
        assertThat(parsed.efficientCoreBaseClockMhz()).isNull();
        assertThat(parsed.performanceCoreBoostClockMhz()).isNull();
        assertThat(parsed.efficientCoreBoostClockMhz()).isNull();
    }

    @Test
    void hybridJsonRoundTripKeepsPowerAndCoreClocksSeparate() {
        var value = cpu(Map.of("processorBasePowerW", new BigDecimal("125.000"),
                "maximumTurboPowerW", new BigDecimal("181.00"),
                "performanceCoreCount", 6, "efficientCoreCount", 8,
                "performanceCoreBaseClockMhz", 3500, "efficientCoreBaseClockMhz", 2600,
                "performanceCoreBoostClockMhz", 5100, "efficientCoreBoostClockMhz", 3900));
        var mapper = JsonMapper.builder().build();
        assertThat(mapper.readValue(mapper.writeValueAsString(value), CatalogSpecification.Cpu.class))
                .isEqualTo(value);
        assertThat(value.tdpW()).isNull();
        assertThat(value.baseClockMhz()).isNull();
        assertThat(value.processorBasePowerW()).isEqualTo(new BigDecimal("125.00"));
        assertThat(value.maximumTurboPowerW()).isEqualTo(new BigDecimal("181.00"));
        assertThat(value.coreCount()).isEqualTo(14);
        assertThat(value.threadCount()).isEqualTo(20);
    }

    @Test
    void partialValuesDoNotInventTotalsTurboPowerOrMissingCoreTypes() {
        var input = new HashMap<String, Object>();
        input.put("coreCount", null);
        input.put("performanceCoreCount", 6);
        input.put("processorBasePowerW", new BigDecimal("125"));
        input.put("performanceCoreBaseClockMhz", 3500);
        var partial = cpu(input);
        assertThat(partial.coreCount()).isNull();
        assertThat(partial.efficientCoreCount()).isNull();
        assertThat(partial.maximumTurboPowerW()).isNull();
        assertThat(partial.performanceCoreBoostClockMhz()).isNull();
    }

    @Test
    void absentCoreTypeUsesZeroInsteadOfUnknownAndHasNoClock() {
        var value = cpu(Map.of("coreCount", 6, "performanceCoreCount", 6,
                "efficientCoreCount", 0, "baseClockMhz", 2500,
                "performanceCoreBaseClockMhz", 2500));
        assertThat(value.efficientCoreCount()).isZero();
        assertThat(value.efficientCoreBaseClockMhz()).isNull();
        assertThat(value.efficientCoreBoostClockMhz()).isNull();
        for (String field : List.of("efficientCoreBaseClockMhz", "efficientCoreBoostClockMhz")) {
            assertThatThrownBy(() -> cpu(Map.of("efficientCoreCount", 0, field, 2600)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> cpu(Map.of("performanceCoreCount", 0,
                "performanceCoreBaseClockMhz", 3500))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInvalidCoreCountsAndContradictoryTotalOrHybridBaseClock() {
        for (String field : List.of("performanceCoreCount", "efficientCoreCount")) {
            for (int value : List.of(-1, 32768, 15)) {
                assertThatThrownBy(() -> cpu(Map.of(field, value)))
                        .as("Reject %s=%s", field, value).isInstanceOf(IllegalArgumentException.class);
            }
        }
        for (Map<String, Object> input : List.<Map<String, Object>>of(
                Map.of("performanceCoreCount", 6, "efficientCoreCount", 9),
                Map.of("performanceCoreCount", 0, "efficientCoreCount", 0),
                Map.of("performanceCoreCount", 6, "efficientCoreCount", 8, "baseClockMhz", 3500))) {
            assertThatThrownBy(() -> cpu(input)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void rejectsNegativeZeroOrRoundedPowerAndReversedPowerLevels() {
        for (String field : List.of("processorBasePowerW", "maximumTurboPowerW")) {
            for (String value : List.of("0", "-1", "125.001", "1000000")) {
                assertThatThrownBy(() -> cpu(Map.of(field, new BigDecimal(value))))
                        .as("Reject %s=%s", field, value).isInstanceOf(IllegalArgumentException.class);
            }
        }
        assertThatThrownBy(() -> cpu(Map.of("processorBasePowerW", new BigDecimal("125"),
                "maximumTurboPowerW", new BigDecimal("124")))).isInstanceOf(IllegalArgumentException.class);
        assertThat(cpu(Map.of("processorBasePowerW", new BigDecimal("125"),
                "maximumTurboPowerW", new BigDecimal("125"))).maximumTurboPowerW())
                .isEqualTo(new BigDecimal("125.00"));
    }

    @Test
    void rejectsNonPositiveOrReversedPerCoreClocks() {
        for (String field : List.of("performanceCoreBaseClockMhz", "efficientCoreBaseClockMhz",
                "performanceCoreBoostClockMhz", "efficientCoreBoostClockMhz")) {
            for (int value : List.of(0, -1)) {
                assertThatThrownBy(() -> cpu(Map.of(field, value))).isInstanceOf(IllegalArgumentException.class);
            }
        }
        for (String kind : List.of("performance", "efficient")) {
            assertThatThrownBy(() -> cpu(Map.of(kind + "CoreBaseClockMhz", 3500,
                    kind + "CoreBoostClockMhz", 3400))).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> cpu(Map.of(kind + "CoreBoostClockMhz", 5200)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private CatalogSpecification.Cpu cpu(Map<String, Object> overrides) {
        var values = new HashMap<String, Object>(Map.of("coreCount", 14, "threadCount", 20,
                "boostClockMhz", 5100));
        values.putAll(overrides);
        return new CatalogSpecification.Cpu("LGA1700", (Integer) values.get("coreCount"),
                (Integer) values.get("threadCount"), (Integer) values.get("baseClockMhz"),
                (Integer) values.get("boostClockMhz"), null, false, null,
                (BigDecimal) values.get("processorBasePowerW"), (BigDecimal) values.get("maximumTurboPowerW"),
                (Integer) values.get("performanceCoreCount"), (Integer) values.get("efficientCoreCount"),
                (Integer) values.get("performanceCoreBaseClockMhz"), (Integer) values.get("efficientCoreBaseClockMhz"),
                (Integer) values.get("performanceCoreBoostClockMhz"), (Integer) values.get("efficientCoreBoostClockMhz"));
    }
}
