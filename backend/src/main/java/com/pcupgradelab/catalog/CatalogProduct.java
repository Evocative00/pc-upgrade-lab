package com.pcupgradelab.catalog;

import com.pcupgradelab.pc.PartType;
import com.pcupgradelab.catalog.identity.CatalogIdentityKind;
import com.pcupgradelab.catalog.identity.CatalogIdentityRequests;
import com.pcupgradelab.catalog.identity.CatalogRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 여러 사용자의 PC가 함께 참조할 공용 제품. 제품 생성 시에는 검증 전·비활성 상태로 저장한다. */
@Entity
@Table(name = "catalog_product")
public class CatalogProduct {
    // ID는 JPA가 생성한다. 외부 요청에서 제품 ID를 직접 지정하지 않는다.
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, length = 128, updatable = false)
    private String id;

    // 기존 SQL의 VARCHAR와 일치하도록 DB 고유 ENUM 대신 문자열 JDBC 타입을 지정한다.
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private PartType type;

    @Column(nullable = false, length = 100)
    private String manufacturer;

    @Column(name = "model_name", nullable = false, length = 255)
    private String modelName;

    @Column(name = "part_number", length = 128)
    private String partNumber;

    @Column(name = "canonical_id", length = 36)
    private String canonicalId;
    @Column(name = "model_id", length = 128)
    private String modelId;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "identity_kind", nullable = false, length = 32)
    private CatalogIdentityKind identityKind = CatalogIdentityKind.LEGACY_UNCLASSIFIED;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 32)
    private CatalogRole role = CatalogRole.UNASSIGNED;
    @Column(name = "identity_evidence_source_id")
    private Long identityEvidenceSourceId;
    @Column(name = "identity_review_scope", length = 1000)
    private String identityReviewScope;
    @Column(name = "identity_reviewed_at")
    private Instant identityReviewedAt;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "verification_status", nullable = false, length = 20)
    private CatalogVerificationStatus verificationStatus = CatalogVerificationStatus.UNVERIFIED;

    @Column(name = "is_active", nullable = false)
    private boolean active = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // JPA가 DB 조회 결과로 객체를 만들 때 사용하는 생성자.
    protected CatalogProduct() { }

    public CatalogProduct(PartType type, String manufacturer, String modelName, String partNumber) {
        if (type == null) {
            throw new IllegalArgumentException("type is required");
        }
        this.type = type;
        this.manufacturer = requiredText(manufacturer, "manufacturer", 100);
        this.modelName = requiredText(modelName, "modelName", 255);
        this.partNumber = optionalText(partNumber, "partNumber", 128);
    }

    private static String requiredText(String value, String fieldName, int maxLength) {
        String normalized = value == null ? null : value.strip();
        if (normalized == null || normalized.isEmpty() || normalized.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + " is required (max " + maxLength + " characters)");
        }
        return normalized;
    }

    private static String optionalText(String value, String fieldName, int maxLength) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip();
        // 부품번호를 모르는 경우 빈 문자열 대신 NULL로 통일한다.
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + " must be at most " + maxLength + " characters");
        }
        return normalized;
    }

    @PrePersist
    void onCreate() {
        // DB의 TIMESTAMP(6)와 같은 정밀도로 맞춰 저장 전후 시각 차이를 줄인다.
        createdAt = updatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    /** 내부 검토 서비스가 출처·종류·공통 ID 충돌을 확인한 뒤 호출한다. 로컬 UUID는 바꾸지 않는다. */
    public void bindReviewedIdentity(CatalogIdentityRequests.ProductBinding input) {
        if (!java.util.Objects.equals(id, input.productId())) throw new IllegalArgumentException("binding product ID differs");
        if (canonicalId != null && !canonicalId.equals(input.canonicalId()))
            throw new IllegalArgumentException("Existing canonical ID must not be replaced");
        if (modelId != null && !modelId.equals(input.modelId()))
            throw new IllegalArgumentException("Existing model binding must not be replaced");
        if (identityKind != CatalogIdentityKind.LEGACY_UNCLASSIFIED && identityKind != input.identityKind())
            throw new IllegalArgumentException("Existing identity classification must not be replaced");
        if (role != CatalogRole.UNASSIGNED && role != input.role())
            throw new IllegalArgumentException("Existing role must not be replaced");
        boolean same = java.util.Objects.equals(canonicalId, input.canonicalId())
                && java.util.Objects.equals(modelId, input.modelId()) && identityKind == input.identityKind() && role == input.role();
        if (same) {
            if (!java.util.Objects.equals(identityEvidenceSourceId, input.evidenceSourceId())
                    || !java.util.Objects.equals(identityReviewScope, input.reviewScope()))
                throw new IllegalArgumentException("Existing identity review differs; explicit re-review is required");
            return;
        }
        canonicalId = input.canonicalId(); modelId = input.modelId(); identityKind = input.identityKind(); role = input.role();
        identityEvidenceSourceId = input.evidenceSourceId(); identityReviewScope = input.reviewScope();
        identityReviewedAt = updatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public String getId() { return id; }
    public PartType getType() { return type; }
    public String getManufacturer() { return manufacturer; }
    public String getModelName() { return modelName; }
    public String getPartNumber() { return partNumber; }
    public String getCanonicalId() { return canonicalId; }
    public String getModelId() { return modelId; }
    public CatalogIdentityKind getIdentityKind() { return identityKind; }
    public CatalogRole getRole() { return role; }
    public Long getIdentityEvidenceSourceId() { return identityEvidenceSourceId; }
    public String getIdentityReviewScope() { return identityReviewScope; }
    public CatalogVerificationStatus getVerificationStatus() { return verificationStatus; }
    public boolean isActive() { return active; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
