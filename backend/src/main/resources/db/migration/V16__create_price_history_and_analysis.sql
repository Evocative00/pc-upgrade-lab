-- 가격 판단 기능 전용 테이블. 기존 카탈로그·가격 테이블은 변경하지 않고 상품 ID로 참조만 한다.
-- ON DELETE CASCADE는 기존 상품 정리 흐름을 이 테이블이 막지 않도록 하기 위한 것이다.

-- 팀이 검토한 관측 파일에서 새로 가져온 가격 이력. 기존 catalog_price_observation과 같은 관측은 넣지 않는다.
-- 품절·수집 실패·누락 날짜는 행을 만들지 않으며 0원이나 보간 값을 저장하지 않는다.
CREATE TABLE price_history_observation (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    product_id VARCHAR(128) NOT NULL,
    source_name VARCHAR(32) NOT NULL,
    external_id VARCHAR(128) NOT NULL,
    source_url VARCHAR(2048) NOT NULL,
    sale_sku VARCHAR(255) NOT NULL,
    sale_unit VARCHAR(16) NOT NULL,
    module_count INTEGER,
    amount_krw BIGINT NOT NULL,
    observed_at TIMESTAMP(6) NOT NULL,
    evidence_url VARCHAR(2048) NOT NULL,
    import_file VARCHAR(255) NOT NULL,
    imported_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_price_history_product FOREIGN KEY (product_id)
        REFERENCES catalog_product(id) ON DELETE CASCADE,
    CONSTRAINT uq_price_history_offer_time UNIQUE (source_name, external_id, observed_at),
    CONSTRAINT ck_price_history_source CHECK (CHAR_LENGTH(TRIM(source_name)) > 0),
    CONSTRAINT ck_price_history_external CHECK (CHAR_LENGTH(TRIM(external_id)) > 0),
    CONSTRAINT ck_price_history_sku CHECK (CHAR_LENGTH(TRIM(sale_sku)) > 0),
    CONSTRAINT ck_price_history_unit CHECK (
        (sale_unit = 'PRODUCT' AND module_count IS NULL)
        OR (sale_unit = 'RAM_KIT' AND module_count IS NOT NULL AND module_count > 0)
    ),
    CONSTRAINT ck_price_history_amount CHECK (amount_krw > 0 AND amount_krw <= 999999999999),
    CONSTRAINT ck_price_history_evidence CHECK (CHAR_LENGTH(TRIM(evidence_url)) > 0)
);
CREATE INDEX idx_price_history_product_time ON price_history_observation(product_id, observed_at);

-- 명시적 분석 작업이 남기는 판정 스냅샷. 같은 상품을 다시 분석하면 새 행을 추가한다.
CREATE TABLE price_analysis_result (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    product_id VARCHAR(128) NOT NULL,
    analyzed_at TIMESTAMP(6) NOT NULL,
    method_version VARCHAR(32) NOT NULL,
    window_start TIMESTAMP(6) NOT NULL,
    window_end TIMESTAMP(6) NOT NULL,
    sample_count INTEGER NOT NULL,
    observed_day_count INTEGER NOT NULL,
    latest_amount_krw BIGINT,
    latest_observed_at TIMESTAMP(6),
    median_krw BIGINT,
    min_krw BIGINT,
    max_krw BIGINT,
    change_rate_7d DECIMAL(9,4),
    verdict VARCHAR(24) NOT NULL,
    reason_code VARCHAR(32) NOT NULL,
    CONSTRAINT fk_price_analysis_product FOREIGN KEY (product_id)
        REFERENCES catalog_product(id) ON DELETE CASCADE,
    CONSTRAINT ck_price_analysis_verdict CHECK (
        verdict IN ('GOOD_PRICE', 'FAIR', 'WAIT', 'INSUFFICIENT_DATA')
    ),
    CONSTRAINT ck_price_analysis_counts CHECK (sample_count >= 0 AND observed_day_count >= 0),
    CONSTRAINT ck_price_analysis_window CHECK (window_start <= window_end)
);
CREATE INDEX idx_price_analysis_product_time ON price_analysis_result(product_id, analyzed_at);
