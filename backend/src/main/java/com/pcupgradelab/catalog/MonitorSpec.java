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

/** 데스크톱 외부 모니터의 제품 사양. 현재 화면 설정과 사용자 목표는 별도로 관리한다. */
@Entity
@Table(name = "monitor_spec")
public class MonitorSpec {
    @Id
    @Column(name = "product_id", nullable = false, length = 128, updatable = false)
    private String productId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private CatalogProduct product;

    @Column(name = "screen_size_inches", precision = 5, scale = 2)
    private BigDecimal screenSizeInches;
    @Column(name = "native_width_px")
    private Integer nativeWidthPx;
    @Column(name = "native_height_px")
    private Integer nativeHeightPx;
    @Column(name = "panel_type", length = 16)
    private String panelType;
    @Column(name = "native_standard_refresh_hz", precision = 7, scale = 3)
    private BigDecimal nativeStandardRefreshHz;
    @Column(name = "has_refresh_overclock")
    private Boolean hasRefreshOverclock;
    @Column(name = "native_oc_refresh_hz", precision = 7, scale = 3)
    private BigDecimal nativeOcRefreshHz;
    @Column(name = "active_power_w", precision = 8, scale = 2)
    private BigDecimal activePowerW;
    @Column(name = "active_power_basis", length = 64)
    private String activePowerBasis;
    @Column(name = "active_power_conditions", length = 1000)
    private String activePowerConditions;

    protected MonitorSpec() { }

    public MonitorSpec(CatalogProduct product, CatalogSpecification.Monitor specification) {
        this.product = CatalogSpecificationValues.productOfType(product, PartType.MONITOR);
        if (specification == null) {
            throw new IllegalArgumentException("Monitor specification is required");
        }
        screenSizeInches = specification.screenSizeInches();
        nativeWidthPx = specification.nativeWidthPx();
        nativeHeightPx = specification.nativeHeightPx();
        panelType = specification.panelType();
        nativeStandardRefreshHz = specification.nativeStandardRefreshHz();
        hasRefreshOverclock = specification.hasRefreshOverclock();
        nativeOcRefreshHz = specification.nativeOcRefreshHz();
        activePowerW = specification.activePowerW();
        activePowerBasis = specification.activePowerBasis();
        activePowerConditions = specification.activePowerConditions();
    }

    public String getProductId() { return productId; }

    public CatalogSpecification.Monitor toSpecification() {
        return new CatalogSpecification.Monitor(screenSizeInches, nativeWidthPx, nativeHeightPx,
                panelType, nativeStandardRefreshHz, hasRefreshOverclock, nativeOcRefreshHz,
                activePowerW, activePowerBasis, activePowerConditions);
    }
}
