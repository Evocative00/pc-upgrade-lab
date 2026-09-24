package com.pcupgradelab.pc;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 사용자가 저장한 PC 한 대. pc_configuration 테이블의 한 행에 대응하는 JPA 엔티티다.
 * 부품(PcPart)의 생성·교체·삭제도 이 객체를 통해 함께 관리한다.
 * 등록·조회·수정 API의 서비스에서 사용하며, 응답은 별도 DTO로 만들어 반환한다.
 */
@Entity
@Table(name = "pc_configuration")
public class PcConfiguration {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    // 소유자 구분 값은 서버가 결정한다. 현재 로컬 개발용 값 자체가 로그인 인증을 제공하지는 않는다.
    @Column(name = "owner_key", nullable = false, length = 128)
    private String ownerKey;
    @Column(nullable = false, length = 100)
    private String name;
    // JPA가 갱신할 때 버전을 비교해 동시 수정 충돌을 감지한다. HTTP 오류 변환은 API 계층의 역할이다.
    @Version
    private long version;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    // PC 저장·삭제를 부품에도 전파한다(cascade). 목록에서 제거된 부품은 DB에서도 삭제한다(orphanRemoval).
    // 실제 외래 키 pc_id의 연결은 PcPart.pc가 관리하며, 조회 시에는 부품 행 ID 순서로 정렬한다.
    @OneToMany(mappedBy = "pc", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<PcPart> parts = new ArrayList<>();

    // JPA가 DB 조회 결과로 엔티티를 만들 때 사용하는 기본 생성자.
    protected PcConfiguration() { }

    public PcConfiguration(String ownerKey, String name, List<PartInput> parts) {
        if (ownerKey == null || ownerKey.isBlank() || ownerKey.length() > 128) {
            throw new IllegalArgumentException("ownerKey is required (max 128 characters)");
        }
        this.ownerKey = ownerKey;
        update(name, parts);
    }

    /**
     * PC ID를 유지하면서 이름과 부품 목록 전체를 교체한다. PUT 요청에서 사용할 동작이다.
     * 저장된 PC를 수정할 때는 서비스의 @Transactional 메서드 안에서 호출한다.
     * 새 부품을 모두 검증한 후 교체하므로 잘못된 입력이 기존 구성을 일부만 바꾸지 않게 한다.
     */
    public void update(String name, List<PartInput> replacement) {
        if (name == null || name.isBlank() || name.length() > 100) {
            throw new IllegalArgumentException("PC name is required (max 100 characters)");
        }
        if (replacement == null || replacement.isEmpty() || replacement.size() > 64) {
            throw new IllegalArgumentException("PC must contain 1 to 64 part entries");
        }
        // PcPart 생성자가 각 항목을 검증한다. 하나라도 잘못되면 아래의 이름·목록 변경 전 예외가 발생한다.
        var next = replacement.stream().map(input -> new PcPart(this, input)).toList();
        this.name = name.strip();
        // JPA가 추적 중인 목록 객체는 유지한다. 트랜잭션 반영 시 이전 부품 행은 삭제되고 새 행이 저장된다.
        this.parts.clear();
        this.parts.addAll(next);
        this.updatedAt = Instant.now();
    }

    // 처음 저장되기 직전에 생성·수정 시간을 설정한다. 이후 수정 시간은 update()에서 갱신한다.
    @PrePersist
    void onCreate() { createdAt = updatedAt = Instant.now(); }

    public Long getId() { return id; }
    public String getOwnerKey() { return ownerKey; }
    public String getName() { return name; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    // 호출한 쪽에서 목록을 직접 추가·삭제하지 못하게 한다. 구성 변경은 update()를 사용한다.
    public List<PcPart> getParts() { return List.copyOf(parts); }
}
