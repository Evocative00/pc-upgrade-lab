-- 국내 신품의 상품가 관측. 기존 장기 기준가격은 변경하지 않는다.
-- 판매 단위가 확인된 판매처 매핑만 사용하며 배송료/할인 조건을 저장하지 않는다.
CREATE TABLE catalog_price_mapping (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    product_id VARCHAR(128) NOT NULL,
    source_name VARCHAR(32) NOT NULL,
    external_id VARCHAR(128) NOT NULL,
    source_url VARCHAR(2048) NOT NULL,
    sale_sku VARCHAR(255) NOT NULL,
    sale_unit VARCHAR(16) NOT NULL,
    module_count INTEGER,
    verified_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_price_mapping_product FOREIGN KEY (product_id) REFERENCES catalog_product(id),
    CONSTRAINT uq_price_mapping_external UNIQUE (source_name, external_id),
    CONSTRAINT uq_price_mapping_product UNIQUE (id, product_id),
    CONSTRAINT ck_price_mapping_source CHECK (CHAR_LENGTH(TRIM(source_name)) > 0),
    CONSTRAINT ck_price_mapping_external CHECK (CHAR_LENGTH(TRIM(external_id)) > 0),
    CONSTRAINT ck_price_mapping_url CHECK (CHAR_LENGTH(TRIM(source_url)) > 0),
    CONSTRAINT ck_price_mapping_sku CHECK (CHAR_LENGTH(TRIM(sale_sku)) > 0),
    CONSTRAINT ck_price_mapping_unit CHECK (
        (sale_unit = 'PRODUCT' AND module_count IS NULL)
        OR (sale_unit = 'RAM_KIT' AND module_count IS NOT NULL AND module_count > 0)
    )
);
CREATE INDEX idx_price_mapping_product ON catalog_price_mapping(product_id);

CREATE TABLE catalog_price_observation (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    mapping_id BIGINT NOT NULL,
    product_id VARCHAR(128) NOT NULL,
    amount_krw BIGINT NOT NULL,
    observed_at TIMESTAMP(6) NOT NULL,
    evidence_url VARCHAR(2048) NOT NULL,
    CONSTRAINT fk_price_observation_mapping FOREIGN KEY (mapping_id, product_id)
        REFERENCES catalog_price_mapping(id, product_id),
    CONSTRAINT fk_price_observation_product FOREIGN KEY (product_id) REFERENCES catalog_product(id),
    CONSTRAINT uq_price_observation_time UNIQUE (mapping_id, observed_at),
    CONSTRAINT ck_price_observation_amount CHECK (amount_krw > 0 AND amount_krw <= 999999999999),
    CONSTRAINT ck_price_observation_evidence CHECK (CHAR_LENGTH(TRIM(evidence_url)) > 0)
);
-- 최신 관측은 observed_at, id 순으로 결정한다. 같은 시각에도 결과가 일정하다.
CREATE INDEX idx_price_observation_latest ON catalog_price_observation(product_id, observed_at, id);
