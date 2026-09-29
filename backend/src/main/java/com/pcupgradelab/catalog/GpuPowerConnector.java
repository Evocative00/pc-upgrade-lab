package com.pcupgradelab.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.util.Objects;

/** 카드 자체의 보조전원 단자 종류와 개수. 변환 어댑터의 입력 단자를 뜻하지 않는다. */
@Embeddable
public class GpuPowerConnector {
    @Column(name = "connector_type", nullable = false, length = 32)
    private String connectorType;

    @Column(name = "connector_count", nullable = false)
    private Short connectorCount;

    protected GpuPowerConnector() { }

    public GpuPowerConnector(CatalogSpecification.GpuPowerConnector specification) {
        if (specification == null) {
            throw new IllegalArgumentException("GPU power connector is required");
        }
        connectorType = specification.connectorType();
        connectorCount = CatalogSpecificationValues.toShort(specification.connectorCount());
    }

    public CatalogSpecification.GpuPowerConnector toSpecification() {
        return new CatalogSpecification.GpuPowerConnector(connectorType,
                CatalogSpecificationValues.toInteger(connectorCount));
    }

    // 같은 종류·개수는 같은 구성 값이다. 별도 엔티티 ID는 필요하지 않다.
    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof GpuPowerConnector that)) return false;
        return Objects.equals(connectorType, that.connectorType)
                && Objects.equals(connectorCount, that.connectorCount);
    }

    @Override
    public int hashCode() { return Objects.hash(connectorType, connectorCount); }
}
