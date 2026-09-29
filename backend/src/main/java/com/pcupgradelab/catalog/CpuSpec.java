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

/** CPU 제품 한 개의 제원. 미확인 값은 기본 수치 대신 NULL로 저장한다. */
@Entity
@Table(name = "cpu_spec")
public class CpuSpec {
    @Id
    @Column(name = "product_id", nullable = false, length = 128, updatable = false)
    private String productId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private CatalogProduct product;

    @Column(name = "socket_code", length = 32)
    private String socketCode;
    @Column(name = "core_count")
    private Short coreCount;
    @Column(name = "thread_count")
    private Short threadCount;
    @Column(name = "base_clock_mhz")
    private Integer baseClockMhz;
    @Column(name = "boost_clock_mhz")
    private Integer boostClockMhz;
    @Column(name = "tdp_w", precision = 8, scale = 2)
    private BigDecimal tdpW;
    @Column(name = "has_integrated_graphics")
    private Boolean hasIntegratedGraphics;
    @Column(name = "integrated_graphics_model", length = 128)
    private String integratedGraphicsModel;

    protected CpuSpec() { }

    public CpuSpec(CatalogProduct product, CatalogSpecification.Cpu specification) {
        this.product = CatalogSpecificationValues.productOfType(product, PartType.CPU);
        if (specification == null) {
            throw new IllegalArgumentException("CPU specification is required");
        }
        // 레코드 생성자가 검사를 마친 값만 복사한다. ID는 저장 시 @MapsId가 연결한다.
        socketCode = specification.socketCode();
        coreCount = CatalogSpecificationValues.toShort(specification.coreCount());
        threadCount = CatalogSpecificationValues.toShort(specification.threadCount());
        baseClockMhz = specification.baseClockMhz();
        boostClockMhz = specification.boostClockMhz();
        tdpW = specification.tdpW();
        hasIntegratedGraphics = specification.hasIntegratedGraphics();
        integratedGraphicsModel = specification.integratedGraphicsModel();
    }

    public String getProductId() { return productId; }

    public CatalogSpecification.Cpu toSpecification() {
        return new CatalogSpecification.Cpu(socketCode, CatalogSpecificationValues.toInteger(coreCount),
                CatalogSpecificationValues.toInteger(threadCount), baseClockMhz, boostClockMhz,
                tdpW, hasIntegratedGraphics, integratedGraphicsModel);
    }
}
