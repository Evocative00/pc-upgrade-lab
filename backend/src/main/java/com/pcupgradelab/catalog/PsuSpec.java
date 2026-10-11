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

/** 정확한 파워 상품의 정격 출력·장착 크기·포함 케이블 제원. */
@Entity
@Table(name = "psu_spec")
public class PsuSpec {
    @Id
    @Column(name = "product_id", nullable = false, length = 128, updatable = false)
    private String productId;
    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private CatalogProduct product;
    @Column(name = "rated_power_w")
    private Integer ratedPowerW;
    @Column(name = "form_factor", length = 32)
    private String formFactor;
    @Column(name = "length_mm", precision = 8, scale = 2)
    private BigDecimal lengthMm;
    @Column(name = "width_mm", precision = 8, scale = 2)
    private BigDecimal widthMm;
    @Column(name = "height_mm", precision = 8, scale = 2)
    private BigDecimal heightMm;
    @Column(name = "atx_version", length = 16)
    private String atxVersion;
    @Column(name = "power_connectors_known", nullable = false)
    private boolean powerConnectorsKnown;
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "psu_power_connector", joinColumns = @JoinColumn(name = "product_id", nullable = false))
    private List<PsuPowerConnector> powerConnectors = new ArrayList<>();

    protected PsuSpec() { }

    public PsuSpec(CatalogProduct product, CatalogSpecification.Psu specification) {
        this.product = CatalogSpecificationValues.productOfType(product, PartType.PSU);
        if (specification == null) throw new IllegalArgumentException("PSU specification is required");
        ratedPowerW = specification.ratedPowerW();
        formFactor = specification.formFactor();
        lengthMm = specification.lengthMm();
        widthMm = specification.widthMm();
        heightMm = specification.heightMm();
        atxVersion = specification.atxVersion();
        powerConnectorsKnown = specification.powerConnectorsKnown();
        specification.powerConnectors().stream().map(PsuPowerConnector::new).forEach(powerConnectors::add);
    }

    public String getProductId() { return productId; }

    public CatalogSpecification.Psu toSpecification() {
        return new CatalogSpecification.Psu(ratedPowerW, formFactor, lengthMm, widthMm, heightMm,
                atxVersion, powerConnectorsKnown, powerConnectors.stream().map(PsuPowerConnector::toSpecification).toList());
    }
}
