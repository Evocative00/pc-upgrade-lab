package com.pcupgradelab.pc;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.util.LinkedHashMap;
import java.util.Map;

@Entity
@Table(name = "pc_part")
public class PcPart {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pc_id", nullable = false)
    private PcConfiguration pc;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private PartType type;
    @Column(name = "display_name", nullable = false, length = 255)
    private String displayName;
    @Column(name = "raw_name", length = 500)
    private String rawName;
    @Column(nullable = false)
    private int quantity;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10)
    private InputSource source;
    @Column(name = "catalog_product_id", length = 128)
    private String catalogProductId;
    @Enumerated(EnumType.STRING) @Column(name = "match_status", nullable = false, length = 20)
    private MatchStatus matchStatus;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "json")
    private Map<String, Object> specs;

    protected PcPart() { }

    PcPart(PcConfiguration pc, PartInput input) {
        if (input == null || input.type() == null || input.displayName() == null
                || input.displayName().isBlank() || input.displayName().length() > 255
                || input.rawName() != null && input.rawName().length() > 500
                || input.quantity() == null || input.quantity() < 1 || input.quantity() > 64
                || input.source() == null || input.specs() == null || input.specs().size() > 40
                || !input.isCatalogLinkValid() || !input.isSpecsValid()
                || input.catalogProductId() != null && input.catalogProductId().length() > 128) {
            throw new IllegalArgumentException("Invalid PC part");
        }
        this.pc = pc;
        type = input.type();
        displayName = input.displayName().strip();
        rawName = input.rawName();
        quantity = input.quantity();
        source = input.source();
        catalogProductId = input.catalogProductId();
        matchStatus = input.matchStatus();
        specs = new LinkedHashMap<>(input.specs());
    }

    public Long getId() { return id; }
    public PartInput toInput() {
        return new PartInput(type, displayName, rawName, quantity, source,
                catalogProductId, matchStatus, specs);
    }
}
