package com.pcupgradelab.catalog.storage;

import com.pcupgradelab.catalog.CatalogProduct;
import com.pcupgradelab.catalog.CatalogSpecification;
import com.pcupgradelab.pc.PartType;
import jakarta.persistence.*;
import java.math.BigDecimal;

/** Manufacturer nominal values for one storage device; no scan-derived capacity inference. */
@Entity
@Table(name = "storage_spec")
public class StorageSpec {
    @Id
    @Column(name = "product_id", nullable = false, length = 128, updatable = false)
    private String productId;
    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private CatalogProduct product;
    @Column(name = "storage_kind", length = 32) private String storageKind;
    @Column(name = "capacity_bytes") private Long capacityBytes;
    @Column(name = "advertised_capacity_gb", precision = 12, scale = 3) private BigDecimal advertisedCapacityGb;
    @Column(name = "capacity_basis", length = 32) private String capacityBasis;
    @Column(name = "form_factor", length = 32) private String formFactor;
    @Column(name = "bus_interface", length = 32) private String busInterface;
    @Column(name = "interface_protocol", length = 32) private String interfaceProtocol;
    @Column(name = "pcie_version", length = 16) private String pcieVersion;
    @Column(name = "pcie_lanes") private Integer pcieLanes;
    @Column(name = "nvme_version", length = 16) private String nvmeVersion;
    @Column(name = "sata_version", length = 16) private String sataVersion;
    @Column(name = "connector_key", length = 32) private String connectorKey;
    @Column(name = "m2_length_code", length = 32) private String m2LengthCode;
    @Column(name = "length_mm", precision = 8, scale = 2) private BigDecimal lengthMm;
    @Column(name = "width_mm", precision = 8, scale = 2) private BigDecimal widthMm;
    @Column(name = "height_mm", precision = 8, scale = 2) private BigDecimal heightMm;
    @Column(name = "dimensions_basis", length = 32) private String dimensionsBasis;
    @Column(name = "heatsink_included") private Boolean heatsinkIncluded;

    protected StorageSpec() { }

    public StorageSpec(CatalogProduct product, CatalogSpecification.Storage value) {
        if (product == null || product.getType() != PartType.STORAGE || value == null) {
            throw new IllegalArgumentException("A STORAGE product and specification are required");
        }
        this.product = product;
        storageKind = value.storageKind(); capacityBytes = value.capacityBytes();
        advertisedCapacityGb = value.advertisedCapacityGb(); capacityBasis = value.capacityBasis();
        formFactor = value.formFactor(); busInterface = value.busInterface();
        interfaceProtocol = value.interfaceProtocol(); pcieVersion = value.pcieVersion();
        pcieLanes = value.pcieLanes(); nvmeVersion = value.nvmeVersion(); sataVersion = value.sataVersion();
        connectorKey = value.connectorKey(); m2LengthCode = value.m2LengthCode();
        lengthMm = value.lengthMm(); widthMm = value.widthMm(); heightMm = value.heightMm();
        dimensionsBasis = value.dimensionsBasis(); heatsinkIncluded = value.heatsinkIncluded();
    }

    public String getProductId() { return productId; }

    public CatalogSpecification.Storage toSpecification() {
        return new CatalogSpecification.Storage(storageKind, capacityBytes, advertisedCapacityGb,
                capacityBasis, formFactor, busInterface, interfaceProtocol, pcieVersion, pcieLanes,
                nvmeVersion, sataVersion, connectorKey, m2LengthCode, lengthMm, widthMm, heightMm,
                dimensionsBasis, heatsinkIncluded);
    }
}
