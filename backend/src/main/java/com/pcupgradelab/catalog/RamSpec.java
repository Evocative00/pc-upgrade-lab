package com.pcupgradelab.catalog;

import com.pcupgradelab.pc.PartType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/** RAM 한 제품 구성의 제원. 모듈 한 장의 용량과 제품에 들어 있는 장수를 구분한다. */
@Entity
@Table(name = "ram_spec")
public class RamSpec {
    @Id
    @Column(name = "product_id", nullable = false, length = 128, updatable = false)
    private String productId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private CatalogProduct product;

    @Column(name = "memory_type", length = 10)
    private String memoryType;
    @Column(name = "module_capacity_bytes")
    private Long moduleCapacityBytes;
    @Column(name = "module_count")
    private Short moduleCount;
    @Column(name = "data_rate_mts")
    private Integer dataRateMts;
    @Column(name = "module_form_factor", length = 16)
    private String moduleFormFactor;
    @Column(name = "pin_count")
    private Short pinCount;
    @Column(name = "is_ecc")
    private Boolean isEcc;
    @Column(name = "buffer_type", length = 16)
    private String bufferType;
    @Column(name = "voltage_v", precision = 5, scale = 3)
    private BigDecimal voltageV;
    @Column(name = "height_mm", precision = 7, scale = 2)
    private BigDecimal heightMm;

    protected RamSpec() { }

    public RamSpec(CatalogProduct product, CatalogSpecification.Ram specification) {
        this.product = CatalogSpecificationValues.productOfType(product, PartType.RAM);
        if (specification == null) {
            throw new IllegalArgumentException("RAM specification is required");
        }
        memoryType = specification.memoryType();
        moduleCapacityBytes = specification.moduleCapacityBytes();
        moduleCount = CatalogSpecificationValues.toShort(specification.moduleCount());
        dataRateMts = specification.dataRateMts();
        moduleFormFactor = specification.moduleFormFactor();
        pinCount = CatalogSpecificationValues.toShort(specification.pinCount());
        isEcc = specification.isEcc();
        bufferType = specification.bufferType();
        voltageV = specification.voltageV();
        heightMm = specification.heightMm();
    }

    public String getProductId() { return productId; }

    public CatalogSpecification.Ram toSpecification() {
        return new CatalogSpecification.Ram(memoryType, moduleCapacityBytes,
                CatalogSpecificationValues.toInteger(moduleCount), dataRateMts, moduleFormFactor,
                CatalogSpecificationValues.toInteger(pinCount), isEcc, bufferType, voltageV, heightMm);
    }
}
