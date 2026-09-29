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

/** 메인보드 제원. 같은 소켓이라는 사실만으로 CPU의 BIOS 지원까지 확정하지 않는다. */
@Entity
@Table(name = "motherboard_spec")
public class MotherboardSpec {
    @Id
    @Column(name = "product_id", nullable = false, length = 128, updatable = false)
    private String productId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private CatalogProduct product;

    @Column(name = "socket_code", length = 32)
    private String socketCode;
    @Column(length = 64)
    private String chipset;
    @Column(name = "form_factor", length = 32)
    private String formFactor;
    @Column(name = "memory_type", length = 10)
    private String memoryType;
    @Column(name = "memory_form_factor", length = 16)
    private String memoryFormFactor;
    @Column(name = "memory_slot_count")
    private Short memorySlotCount;
    @Column(name = "max_memory_bytes")
    private Long maxMemoryBytes;
    @Column(name = "supports_ecc")
    private Boolean supportsEcc;

    protected MotherboardSpec() { }

    public MotherboardSpec(CatalogProduct product, CatalogSpecification.Motherboard specification) {
        this.product = CatalogSpecificationValues.productOfType(product, PartType.MOTHERBOARD);
        if (specification == null) {
            throw new IllegalArgumentException("motherboard specification is required");
        }
        socketCode = specification.socketCode();
        chipset = specification.chipset();
        formFactor = specification.formFactor();
        memoryType = specification.memoryType();
        memoryFormFactor = specification.memoryFormFactor();
        memorySlotCount = CatalogSpecificationValues.toShort(specification.memorySlotCount());
        maxMemoryBytes = specification.maxMemoryBytes();
        supportsEcc = specification.supportsEcc();
    }

    public String getProductId() { return productId; }

    public CatalogSpecification.Motherboard toSpecification() {
        return new CatalogSpecification.Motherboard(socketCode, chipset, formFactor, memoryType,
                memoryFormFactor, CatalogSpecificationValues.toInteger(memorySlotCount),
                maxMemoryBytes, supportsEcc);
    }
}
