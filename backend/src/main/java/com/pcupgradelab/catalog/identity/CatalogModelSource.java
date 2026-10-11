package com.pcupgradelab.catalog.identity;

import com.pcupgradelab.catalog.CatalogSourceInput;
import com.pcupgradelab.catalog.CatalogSourceName;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "catalog_model_source")
public class CatalogModelSource {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "model_id", nullable = false)
    private CatalogModel model;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR) @Column(name = "source_name", nullable = false, length = 32)
    private CatalogSourceName sourceName;
    @Column(name = "external_id", length = 128)
    private String externalId;
    @Column(name = "source_revision", length = 64)
    private String sourceRevision;
    @Column(name = "source_url", nullable = false, length = 2048)
    private String sourceUrl;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "raw_payload", columnDefinition = "json")
    private Map<String, Object> rawPayload;
    @Column(name = "retrieved_at", nullable = false)
    private Instant retrievedAt;
    @Column(name = "review_scope", nullable = false, length = 1000)
    private String reviewScope;

    protected CatalogModelSource() { }

    CatalogModelSource(CatalogModel model, CatalogIdentityRequests.ModelSource input) {
        this.model = model;
        var source = input.source();
        sourceName = source.sourceName(); externalId = source.externalId(); sourceRevision = source.sourceRevision();
        sourceUrl = source.sourceUrl(); rawPayload = source.rawPayload(); retrievedAt = source.retrievedAt();
        reviewScope = input.reviewScope();
    }

    boolean matches(CatalogIdentityRequests.ModelSource input) {
        var source = input.source();
        return sourceName == source.sourceName() && Objects.equals(externalId, source.externalId())
                && Objects.equals(sourceRevision, source.sourceRevision()) && sourceUrl.equals(source.sourceUrl())
                && retrievedAt.equals(source.retrievedAt()) && reviewScope.equals(input.reviewScope())
                && IdentityValues.sameJson(rawPayload, source.rawPayload());
    }

    public Long getId() { return id; }
    public CatalogModel getModel() { return model; }
    public CatalogSourceName getSourceName() { return sourceName; }
    public String getExternalId() { return externalId; }
    public String getSourceRevision() { return sourceRevision; }
    public String getSourceUrl() { return sourceUrl; }
    public Instant getRetrievedAt() { return retrievedAt; }
    public String getReviewScope() { return reviewScope; }
}
