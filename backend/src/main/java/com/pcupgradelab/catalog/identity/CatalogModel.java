package com.pcupgradelab.catalog.identity;

import com.pcupgradelab.catalog.CatalogVerificationStatus;
import com.pcupgradelab.pc.PartType;
import jakarta.persistence.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 설치 식별용 모델. 등록만으로 정확 제품이나 판매 SKU를 확정하지 않는다. */
@Entity
@Table(name = "catalog_model")
public class CatalogModel {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, length = 128, updatable = false)
    private String id;
    @Column(name = "canonical_id", nullable = false, length = 36, updatable = false)
    private String canonicalId;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR) @Column(nullable = false, length = 20)
    private PartType type;
    @Column(nullable = false, length = 100)
    private String manufacturer;
    @Column(name = "model_name", nullable = false, length = 255)
    private String modelName;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR) @Column(nullable = false, length = 32)
    private CatalogModelKind kind;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR) @Column(nullable = false, length = 32)
    private CatalogRole role;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "verification_status", nullable = false, length = 20)
    private CatalogVerificationStatus verificationStatus = CatalogVerificationStatus.UNVERIFIED;
    @Column(length = 100)
    private String family;
    @Column(length = 128)
    private String series;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CatalogModel() { }

    CatalogModel(CatalogIdentityRequests.ModelRegistration input) {
        canonicalId = input.canonicalId();
        type = input.type();
        manufacturer = input.manufacturer();
        modelName = input.modelName();
        kind = input.kind();
        role = input.role();
        family = input.family();
        series = input.series();
    }

    @PrePersist
    void onCreate() { createdAt = updatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS); }

    boolean matches(CatalogIdentityRequests.ModelRegistration input) {
        return type == input.type() && manufacturer.equals(input.manufacturer())
                && modelName.equals(input.modelName()) && kind == input.kind() && role == input.role()
                && java.util.Objects.equals(family, input.family()) && java.util.Objects.equals(series, input.series());
    }

    public String getId() { return id; }
    public String getCanonicalId() { return canonicalId; }
    public PartType getType() { return type; }
    public String getManufacturer() { return manufacturer; }
    public String getModelName() { return modelName; }
    public CatalogModelKind getKind() { return kind; }
    public CatalogRole getRole() { return role; }
    public CatalogVerificationStatus getVerificationStatus() { return verificationStatus; }
    public String getFamily() { return family; }
    public String getSeries() { return series; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
