package com.pcupgradelab.catalog.identity;

import jakarta.persistence.*;

@Entity
@Table(name = "catalog_model_alias")
public class CatalogModelAlias {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "model_id", nullable = false)
    private CatalogModel model;
    @Column(name = "raw_alias", nullable = false, length = 500)
    private String rawAlias;
    @Column(name = "normalized_alias", nullable = false, length = 500)
    private String normalizedAlias;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "evidence_source_id", nullable = false)
    private CatalogModelSource source;

    protected CatalogModelAlias() { }

    CatalogModelAlias(CatalogModel model, String rawAlias, CatalogModelSource source) {
        if (!model.getId().equals(source.getModel().getId())) throw new IllegalArgumentException("alias source belongs to another model");
        this.model = model; this.rawAlias = rawAlias; normalizedAlias = IdentityValues.alias(rawAlias); this.source = source;
    }

    public Long getId() { return id; }
    public String getRawAlias() { return rawAlias; }
    public String getNormalizedAlias() { return normalizedAlias; }
    public CatalogModelSource getSource() { return source; }
}
