package com.pcupgradelab.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.util.Objects;

/** 포함 케이블의 부품 쪽 플러그 구성. PSU 포트와 변환 어댑터를 포함하지 않는다. */
@Embeddable
public class PsuPowerConnector {
    @Column(name = "connector_type", nullable = false, length = 32)
    private String connectorType;
    @Column(name = "connector_count", nullable = false)
    private Short connectorCount;
    @Column(name = "independent_cable_count")
    private Short independentCableCount;
    @Column(name = "max_power_w")
    private Integer maxPowerW;

    protected PsuPowerConnector() { }

    public PsuPowerConnector(CatalogSpecification.PsuPowerConnector specification) {
        if (specification == null) throw new IllegalArgumentException("PSU power connector is required");
        connectorType = specification.connectorType();
        connectorCount = CatalogSpecificationValues.toShort(specification.connectorCount());
        independentCableCount = CatalogSpecificationValues.toShort(specification.independentCableCount());
        maxPowerW = specification.maxPowerW();
    }

    public CatalogSpecification.PsuPowerConnector toSpecification() {
        return new CatalogSpecification.PsuPowerConnector(connectorType,
                CatalogSpecificationValues.toInteger(connectorCount),
                CatalogSpecificationValues.toInteger(independentCableCount), maxPowerW);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof PsuPowerConnector that)) return false;
        return Objects.equals(connectorType, that.connectorType)
                && Objects.equals(connectorCount, that.connectorCount)
                && Objects.equals(independentCableCount, that.independentCableCount)
                && Objects.equals(maxPowerW, that.maxPowerW);
    }

    @Override
    public int hashCode() { return Objects.hash(connectorType, connectorCount, independentCableCount, maxPowerW); }
}
