-- 여러 사용자의 현재 PC와 교체 초안이 함께 참조할 공용 부품 정보다.
-- 이번 단계는 공통 정보와 가격 저장 공간만 만든다. 종류별 제원/적재/API는 다음 단계다.
CREATE TABLE catalog_product (
    id VARCHAR(128) NOT NULL PRIMARY KEY,
    type VARCHAR(20) NOT NULL,
    manufacturer VARCHAR(100) NOT NULL,
    model_name VARCHAR(255) NOT NULL,
    part_number VARCHAR(128),
    verification_status VARCHAR(20) NOT NULL DEFAULT 'UNVERIFIED',
    is_active BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT ck_catalog_product_id CHECK (CHAR_LENGTH(TRIM(id)) > 0),
    CONSTRAINT ck_catalog_product_type CHECK (
        type IN ('CPU', 'GPU', 'MOTHERBOARD', 'RAM', 'STORAGE', 'PSU', 'CASE', 'COOLER', 'MONITOR')
    ),
    CONSTRAINT ck_catalog_product_manufacturer CHECK (CHAR_LENGTH(TRIM(manufacturer)) > 0),
    CONSTRAINT ck_catalog_product_model CHECK (CHAR_LENGTH(TRIM(model_name)) > 0),
    CONSTRAINT ck_catalog_product_part_number CHECK (
        part_number IS NULL OR CHAR_LENGTH(TRIM(part_number)) > 0
    ),
    CONSTRAINT ck_catalog_product_verification CHECK (
        verification_status IN ('UNVERIFIED', 'PARTIAL', 'CORE_VERIFIED')
    )
);
CREATE INDEX idx_catalog_product_type_active ON catalog_product(type, is_active);

-- 제품별 현재 고정 기준가격 한 건을 보관한다.
-- 가격 자료가 없으면 행은 만들되 amount_krw는 NULL, status는 UNCONFIRMED로 둔다.
-- 제품/가격 행을 함께 만드는 동작은 다음 단계의 저장 서비스에서 한 트랜잭션으로 처리한다.
CREATE TABLE catalog_reference_price (
    product_id VARCHAR(128) NOT NULL PRIMARY KEY,
    amount_krw DECIMAL(14,2),
    status VARCHAR(24) NOT NULL DEFAULT 'UNCONFIRMED',
    method VARCHAR(32),
    period_start DATE,
    period_end DATE,
    observed_day_count INTEGER,
    sample_count INTEGER,
    price_basis VARCHAR(500),
    evidence_ref VARCHAR(2048),
    calculated_at TIMESTAMP(6),
    confirmed_at TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_catalog_price_product FOREIGN KEY (product_id) REFERENCES catalog_product(id),
    CONSTRAINT ck_catalog_price_status CHECK (
        status IN ('UNCONFIRMED', 'INSUFFICIENT_HISTORY', 'CONFIRMED')
    ),
    CONSTRAINT ck_catalog_price_period CHECK (
        (period_start IS NULL AND period_end IS NULL)
        OR (period_start IS NOT NULL AND period_end IS NOT NULL AND period_start <= period_end)
    ),
    CONSTRAINT ck_catalog_price_observed_days CHECK (
        observed_day_count IS NULL OR observed_day_count > 0
    ),
    CONSTRAINT ck_catalog_price_samples CHECK (
        sample_count IS NULL OR sample_count > 0
    ),
    CONSTRAINT ck_catalog_price_sample_count CHECK (
        sample_count IS NULL OR observed_day_count IS NULL OR sample_count >= observed_day_count
    ),
    CONSTRAINT ck_catalog_price_day_coverage CHECK (
        observed_day_count IS NULL OR period_start IS NULL OR period_end IS NULL
        OR observed_day_count <= TIMESTAMPDIFF(DAY, period_start, period_end) + 1
    ),
    CONSTRAINT ck_catalog_price_method CHECK (method IS NULL OR CHAR_LENGTH(TRIM(method)) > 0),
    CONSTRAINT ck_catalog_price_basis CHECK (price_basis IS NULL OR CHAR_LENGTH(TRIM(price_basis)) > 0),
    CONSTRAINT ck_catalog_price_evidence CHECK (evidence_ref IS NULL OR CHAR_LENGTH(TRIM(evidence_ref)) > 0),
    -- CHECK는 결과가 NULL일 때도 통과하므로 확정 분기에 필수 값의 IS NOT NULL도 명시한다.
    CONSTRAINT ck_catalog_price_state CHECK (
        (status IN ('UNCONFIRMED', 'INSUFFICIENT_HISTORY')
            AND amount_krw IS NULL AND confirmed_at IS NULL)
        OR (status = 'CONFIRMED'
            AND amount_krw IS NOT NULL AND amount_krw > 0
            AND method IS NOT NULL
            AND period_start IS NOT NULL AND period_end IS NOT NULL
            AND observed_day_count IS NOT NULL AND sample_count IS NOT NULL
            AND price_basis IS NOT NULL AND evidence_ref IS NOT NULL
            AND calculated_at IS NOT NULL AND confirmed_at IS NOT NULL
            AND confirmed_at >= calculated_at)
    )
);

-- 6개월의 정확한 기간 경계, 가격 산식과 자료의 충분성은 가격 확정 서비스에서 확인한다.
-- 기존 pc_part에는 임시 제품 ID가 남아 있을 수 있으므로 이 단계에서 새 FK를 붙이지 않는다.
