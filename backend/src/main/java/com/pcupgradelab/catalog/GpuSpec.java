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

/** 데스크톱 외장 그래픽카드의 정확한 판매 제품 한 개에 해당하는 제원이다. */
@Entity
@Table(name = "gpu_spec")
public class GpuSpec {
    @Id
    @Column(name = "product_id", nullable = false, length = 128, updatable = false)
    private String productId;

    // 제품 ID를 제원 행의 PK이자 FK로 사용한다. 저장 시 @MapsId가 값을 연결한다.
    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private CatalogProduct product;

    @Column(name = "chip_vendor", length = 16)
    private String chipVendor;
    @Column(length = 128)
    private String chipset;
    @Column(name = "vram_bytes")
    private Long vramBytes;
    @Column(name = "memory_type", length = 16)
    private String memoryType;
    @Column(name = "pcie_version", length = 16)
    private String pcieVersion;
    // 단자의 물리 크기와 실제 사용하는 전기적 레인 수를 서로 다른 값으로 보관한다.
    @Column(name = "pcie_connector_lanes")
    private Short pcieConnectorLanes;
    @Column(name = "pcie_active_lanes")
    private Short pcieActiveLanes;
    @Column(name = "length_mm", precision = 8, scale = 2)
    private BigDecimal lengthMm;
    @Column(name = "height_mm", precision = 8, scale = 2)
    private BigDecimal heightMm;
    @Column(name = "thickness_mm", precision = 8, scale = 2)
    private BigDecimal thicknessMm;
    // 제조사가 밝힌 슬롯 폭이다. mm 두께에서 임의로 계산하지 않는다.
    @Column(name = "slot_width", precision = 4, scale = 2)
    private BigDecimal slotWidth;
    @Column(name = "card_power_w", precision = 8, scale = 2)
    private BigDecimal cardPowerW;
    @Column(name = "card_power_basis", length = 64)
    private String cardPowerBasis;
    @Column(name = "psu_requirement_w")
    private Integer psuRequirementW;
    @Column(name = "psu_requirement_basis", length = 16)
    private String psuRequirementBasis;
    @Column(name = "power_connectors_known", nullable = false)
    private boolean powerConnectorsKnown;

    // 별도 제품 엔티티가 아닌 이 카드의 구성 값이다. 제원 저장/삭제와 함께 관리된다.
    // 목록 순서는 DB에 의존하지 않으며 조회용 record가 정렬과 불변 복사를 담당한다.
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "gpu_power_connector",
            joinColumns = @JoinColumn(name = "product_id", nullable = false))
    private List<GpuPowerConnector> powerConnectors = new ArrayList<>();

    protected GpuSpec() { }

    public GpuSpec(CatalogProduct product, CatalogSpecification.Gpu specification) {
        this.product = CatalogSpecificationValues.productOfType(product, PartType.GPU);
        if (specification == null) {
            throw new IllegalArgumentException("GPU specification is required");
        }
        chipVendor = specification.chipVendor();
        chipset = specification.chipset();
        vramBytes = specification.vramBytes();
        memoryType = specification.memoryType();
        pcieVersion = specification.pcieVersion();
        pcieConnectorLanes = CatalogSpecificationValues.toShort(specification.pcieConnectorLanes());
        pcieActiveLanes = CatalogSpecificationValues.toShort(specification.pcieActiveLanes());
        lengthMm = specification.lengthMm();
        heightMm = specification.heightMm();
        thicknessMm = specification.thicknessMm();
        slotWidth = specification.slotWidth();
        cardPowerW = specification.cardPowerW();
        cardPowerBasis = specification.cardPowerBasis();
        psuRequirementW = specification.psuRequirementW();
        psuRequirementBasis = specification.psuRequirementBasis();
        powerConnectorsKnown = specification.powerConnectorsKnown();
        specification.powerConnectors().stream().map(GpuPowerConnector::new)
                .forEach(powerConnectors::add);
    }

    public String getProductId() { return productId; }

    /** JPA가 관리하는 변경 가능한 목록 대신 불변인 조회 값을 반환한다. 트랜잭션 안에서 호출한다. */
    public CatalogSpecification.Gpu toSpecification() {
        var connectors = powerConnectors.stream().map(GpuPowerConnector::toSpecification).toList();
        return new CatalogSpecification.Gpu(chipVendor, chipset, vramBytes, memoryType, pcieVersion,
                CatalogSpecificationValues.toInteger(pcieConnectorLanes),
                CatalogSpecificationValues.toInteger(pcieActiveLanes),
                lengthMm, heightMm, thicknessMm, slotWidth, cardPowerW, cardPowerBasis,
                psuRequirementW, psuRequirementBasis, powerConnectorsKnown, connectors);
    }
}
