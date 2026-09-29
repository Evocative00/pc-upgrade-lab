-- 제품별 원본과 참고 링크를 보관하는 최소 출처 구조다.
-- 출처 등록/가져온 시각은 제조사 검증 완료를 뜻하지 않는다.
CREATE TABLE catalog_product_source (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    product_id VARCHAR(128) NOT NULL,
    source_name VARCHAR(32) NOT NULL,
    external_id VARCHAR(128),
    source_revision VARCHAR(64),
    source_url VARCHAR(2048) NOT NULL,
    raw_payload JSON,
    retrieved_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_catalog_source_product FOREIGN KEY (product_id) REFERENCES catalog_product(id),
    -- 버전이 바뀌어도 같은 원본은 같은 매핑을 갱신한다. 버전을 고유 키에 넣지 않는다.
    CONSTRAINT uq_catalog_source_external UNIQUE (source_name, external_id),
    CONSTRAINT ck_catalog_source_name CHECK (
        source_name IN ('BUILDCORES', 'MANUFACTURER', 'MANUAL')
    ),
    CONSTRAINT ck_catalog_source_external CHECK (
        external_id IS NULL OR CHAR_LENGTH(TRIM(external_id)) > 0
    ),
    CONSTRAINT ck_catalog_source_revision CHECK (
        source_revision IS NULL OR CHAR_LENGTH(TRIM(source_revision)) > 0
    ),
    CONSTRAINT ck_catalog_source_url CHECK (CHAR_LENGTH(TRIM(source_url)) > 0),
    -- BuildCores는 opendb_id, Git 커밋, 가져온 원본을 함께 기록한다.
    -- 제조사 웹페이지처럼 JSON 원본이 없는 출처는 raw_payload를 NULL로 둘 수 있다.
    CONSTRAINT ck_catalog_source_buildcores CHECK (
        source_name <> 'BUILDCORES'
        OR (external_id IS NOT NULL AND source_revision IS NOT NULL AND raw_payload IS NOT NULL)
    )
);
CREATE INDEX idx_catalog_source_product ON catalog_product_source(product_id);

-- external_id가 NULL인 제조사/수동 출처는 한 제품에 여러 건 연결할 수 있다.
-- 이후 저장 서비스는 URL/커밋 형식과 JSON 객체·opendb_id 일치를 검증한다.
-- JSON 타입은 구조화된 값을 보존하며 원본 파일의 공백·키 순서까지 보존하지는 않는다.
-- 원본 갱신만으로 제조사 확인 제원을 덮어쓰거나 verification_status를 올리지 않는다.
-- 이 표는 원본별 최근 상태다. 변경 이력/검증 항목별 기록은 후속 기능이다.
