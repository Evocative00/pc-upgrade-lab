package com.pcupgradelab.catalog;

import com.pcupgradelab.pc.PartType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** 정확한 케이스 상품의 지원 규격과 공표 한도·조건. 목록의 완전성도 함께 저장한다. */
@Entity
@Table(name = "case_spec")
public class CaseSpec {
    @Id
    @Column(name = "product_id", nullable = false, length = 128, updatable = false)
    private String productId;
    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private CatalogProduct product;
    @Column(name = "motherboard_form_factors_known", nullable = false)
    private boolean motherboardFormFactorsKnown;
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "case_motherboard_form_factor", joinColumns = @JoinColumn(name = "product_id", nullable = false))
    @Column(name = "form_factor", nullable = false, length = 32)
    private List<String> motherboardFormFactors = new ArrayList<>();
    @Column(name = "psu_form_factors_known", nullable = false)
    private boolean psuFormFactorsKnown;
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "case_psu_form_factor", joinColumns = @JoinColumn(name = "product_id", nullable = false))
    @Column(name = "form_factor", nullable = false, length = 32)
    private List<String> psuFormFactors = new ArrayList<>();
    @Column(name = "max_psu_length_mm", precision = 8, scale = 2)
    private BigDecimal maxPsuLengthMm;
    @Column(name = "max_gpu_length_mm", precision = 8, scale = 2)
    private BigDecimal maxGpuLengthMm;
    @Column(name = "max_gpu_height_mm", precision = 8, scale = 2)
    private BigDecimal maxGpuHeightMm;
    @Column(name = "max_gpu_thickness_mm", precision = 8, scale = 2)
    private BigDecimal maxGpuThicknessMm;
    @Column(name = "expansion_slot_count")
    private Short expansionSlotCount;
    @Column(name = "gpu_clearance_conditions", length = 2000)
    private String gpuClearanceConditions;
    @Column(name = "psu_clearance_conditions", length = 2000)
    private String psuClearanceConditions;
    @Column(name = "gpu_clearance_unconditional", nullable = false)
    private boolean gpuClearanceUnconditional;
    @Column(name = "psu_clearance_unconditional", nullable = false)
    private boolean psuClearanceUnconditional;

    protected CaseSpec() { }

    public CaseSpec(CatalogProduct product, CatalogSpecification.Case specification) {
        this.product = CatalogSpecificationValues.productOfType(product, PartType.CASE);
        if (specification == null) throw new IllegalArgumentException("Case specification is required");
        motherboardFormFactorsKnown = specification.motherboardFormFactorsKnown();
        motherboardFormFactors.addAll(specification.motherboardFormFactors());
        psuFormFactorsKnown = specification.psuFormFactorsKnown();
        psuFormFactors.addAll(specification.psuFormFactors());
        maxPsuLengthMm = specification.maxPsuLengthMm();
        maxGpuLengthMm = specification.maxGpuLengthMm();
        maxGpuHeightMm = specification.maxGpuHeightMm();
        maxGpuThicknessMm = specification.maxGpuThicknessMm();
        expansionSlotCount = CatalogSpecificationValues.toShort(specification.expansionSlotCount());
        gpuClearanceConditions = specification.gpuClearanceConditions();
        psuClearanceConditions = specification.psuClearanceConditions();
        gpuClearanceUnconditional = specification.gpuClearanceUnconditional();
        psuClearanceUnconditional = specification.psuClearanceUnconditional();
    }

    public String getProductId() { return productId; }

    public CatalogSpecification.Case toSpecification() {
        return new CatalogSpecification.Case(motherboardFormFactorsKnown, motherboardFormFactors,
                psuFormFactorsKnown, psuFormFactors, maxPsuLengthMm, maxGpuLengthMm, maxGpuHeightMm,
                maxGpuThicknessMm, CatalogSpecificationValues.toInteger(expansionSlotCount),
                gpuClearanceConditions, psuClearanceConditions, gpuClearanceUnconditional, psuClearanceUnconditional);
    }
}
