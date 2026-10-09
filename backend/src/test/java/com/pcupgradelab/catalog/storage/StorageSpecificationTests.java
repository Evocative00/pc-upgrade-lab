package com.pcupgradelab.catalog.storage;

import com.pcupgradelab.catalog.CatalogProduct;
import com.pcupgradelab.catalog.CatalogSpecification;
import com.pcupgradelab.pc.PartType;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class StorageSpecificationTests {
    @Test
    void decimalNominalCapacityRejectsGiBConversionAndKeepsMaximumCasingDimensions() {
        var value = drive(1_000_000_000_000L, "PCIE", "NVME", "5.0", 4, "2.0", null);
        assertThat(value.type()).isEqualTo(PartType.STORAGE);
        assertThat(value.advertisedCapacityGb()).isEqualTo(new BigDecimal("1000.000"));
        assertThat(value.m2LengthCode()).isEqualTo("2280");
        assertThat(value.lengthMm()).isEqualTo(new BigDecimal("80.15"));
        assertThat(value.widthMm()).isEqualTo(new BigDecimal("22.15"));
        assertThat(value.dimensionsBasis()).isEqualTo("MANUFACTURER_MAXIMUM");
        assertThatThrownBy(() -> drive(1_099_511_627_776L, "PCIE", "NVME", "5.0", 4, "2.0", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("decimal");
    }

    @Test
    void sataAllowsUnknownCommandProtocolButNeverPcieOrNvmeFacts() {
        var sata = drive(1_000_000_000_000L, "SATA", null, null, null, null, "3.0");
        assertThat(sata.interfaceProtocol()).isNull();
        assertThat(sata.pcieVersion()).isNull();
        assertThat(sata.pcieLanes()).isNull();
        assertThat(sata.nvmeVersion()).isNull();
        assertThat(sata.busInterface()).isEqualTo("SATA");
        assertThatThrownBy(() -> drive(1_000_000_000_000L, "SATA", "NVME", null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> drive(1_000_000_000_000L, "SATA", null, "4.0", 4, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> drive(1_000_000_000_000L, "PCIE", "NVME", "4.0", 4, "1.4", "3.0"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> drive(1_000_000_000_000L, null, "NVME", null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unknownFieldsRemainNullAndProductTypeIsEnforced() {
        var unknown = new CatalogSpecification.Storage(null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null);
        assertThat(unknown.capacityBytes()).isNull();
        assertThat(unknown.heatsinkIncluded()).isNull();
        assertThatThrownBy(() -> new StorageSpec(new CatalogProduct(PartType.CPU, "Fixture", "CPU", null), unknown))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CatalogSpecification.Storage("SSD", 1_000_000_000_000L,
                new BigDecimal("1000"), null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null)).isInstanceOf(IllegalArgumentException.class);
    }

    static CatalogSpecification.Storage drive(Long bytes, String bus, String protocol, String pcie,
                                               Integer lanes, String nvme, String sata) {
        return new CatalogSpecification.Storage("SSD", bytes, new BigDecimal("1000"), "DECIMAL_GB",
                "M2", bus, protocol, pcie, lanes, nvme, sata, "M", "2280", new BigDecimal("80.15"),
                new BigDecimal("22.15"), new BigDecimal("2.38"), "MANUFACTURER_MAXIMUM", null);
    }
}
