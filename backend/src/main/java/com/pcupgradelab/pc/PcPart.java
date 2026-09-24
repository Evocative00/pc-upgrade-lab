package com.pcupgradelab.pc;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 저장된 PC에 속한 부품 한 항목. 같은 모델의 RAM이나 저장장치도 여러 행으로 보존할 수 있다.
 * PcConfiguration이 부품 목록을 관리하므로 생성자는 같은 패키지에서만 사용한다.
 */
@Entity
@Table(name = "pc_part")
public class PcPart {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    // 여러 부품이 한 PC를 참조한다. LAZY는 PC 객체가 필요할 때 조회하도록 하는 설정이다.
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
    // 종류마다 다른 제원을 JSON으로 저장한다. 큰 용량 값과 미확인 값(null)도 보존한다.
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "json")
    private Map<String, Object> specs;

    protected PcPart() { }

    PcPart(PcConfiguration pc, PartInput input) {
        // HTTP의 @Valid 검증을 거치지 않고 서비스나 테스트에서 생성해도 잘못된 부품이 들어오지 않게 한다.
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
    /** 공통 부품 형식으로 변환한다. API의 상세 DTO를 만들 때 사용하며 DB 내부 부품 ID는 포함하지 않는다. */
    public PartInput toInput() {
        return new PartInput(type, displayName, rawName, quantity, source,
                catalogProductId, matchStatus, specs);
    }
}
