-- 파워·케이스 정확 상품의 전용 제원이다. 기존 상품·가격·PC 연결은 변경하지 않는다.
-- 마이그레이션은 구조만 추가하며 상품·근거·가격 자료를 적재하지 않는다.
CREATE TABLE psu_spec (
    product_id VARCHAR(128) NOT NULL PRIMARY KEY,
    rated_power_w INTEGER,
    form_factor VARCHAR(32),
    length_mm DECIMAL(8,2),
    width_mm DECIMAL(8,2),
    height_mm DECIMAL(8,2),
    atx_version VARCHAR(16),
    power_connectors_known BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_psu_spec_product FOREIGN KEY (product_id) REFERENCES catalog_product(id),
    CONSTRAINT ck_psu_power CHECK (rated_power_w IS NULL OR rated_power_w > 0),
    CONSTRAINT ck_psu_form CHECK (form_factor IS NULL OR form_factor IN ('ATX', 'SFX', 'SFX_L')),
    CONSTRAINT ck_psu_length CHECK (length_mm IS NULL OR length_mm > 0),
    CONSTRAINT ck_psu_width CHECK (width_mm IS NULL OR width_mm > 0),
    CONSTRAINT ck_psu_height CHECK (height_mm IS NULL OR height_mm > 0),
    CONSTRAINT ck_psu_atx_version CHECK (atx_version IS NULL OR CHAR_LENGTH(TRIM(atx_version)) > 0),
    CONSTRAINT ck_psu_connectors_known CHECK (power_connectors_known IN (FALSE, TRUE))
);

-- 플러그 수와 독립 케이블 수를 구분한다. max_power_w는 케이블 한 개의 공표 전달 한도다.
CREATE TABLE psu_power_connector (
    product_id VARCHAR(128) NOT NULL,
    connector_type VARCHAR(32) NOT NULL,
    connector_count SMALLINT NOT NULL,
    independent_cable_count SMALLINT,
    max_power_w INTEGER,
    PRIMARY KEY (product_id, connector_type),
    CONSTRAINT fk_psu_connector_spec FOREIGN KEY (product_id) REFERENCES psu_spec(product_id),
    CONSTRAINT ck_psu_connector_type CHECK (connector_type IN (
        'ATX_24PIN', 'EPS_4_4PIN', 'PCIE_6_2PIN', 'PCIE_6PIN', 'PCIE_8PIN',
        'PCIE_16PIN_UNSPECIFIED', 'PCIE_12VHPWR', 'PCIE_12V_2X6')),
    CONSTRAINT ck_psu_connector_count CHECK (connector_count > 0),
    CONSTRAINT ck_psu_cable_count CHECK (independent_cable_count IS NULL
        OR (independent_cable_count > 0 AND independent_cable_count <= connector_count)),
    CONSTRAINT ck_psu_cable_power CHECK (max_power_w IS NULL OR max_power_w > 0)
);

CREATE TABLE case_spec (
    product_id VARCHAR(128) NOT NULL PRIMARY KEY,
    motherboard_form_factors_known BOOLEAN NOT NULL DEFAULT FALSE,
    psu_form_factors_known BOOLEAN NOT NULL DEFAULT FALSE,
    max_psu_length_mm DECIMAL(8,2),
    max_gpu_length_mm DECIMAL(8,2),
    max_gpu_height_mm DECIMAL(8,2),
    max_gpu_thickness_mm DECIMAL(8,2),
    expansion_slot_count SMALLINT,
    gpu_clearance_conditions VARCHAR(2000),
    psu_clearance_conditions VARCHAR(2000),
    gpu_clearance_unconditional BOOLEAN NOT NULL DEFAULT FALSE,
    psu_clearance_unconditional BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_case_spec_product FOREIGN KEY (product_id) REFERENCES catalog_product(id),
    CONSTRAINT ck_case_board_known CHECK (motherboard_form_factors_known IN (FALSE, TRUE)),
    CONSTRAINT ck_case_psu_known CHECK (psu_form_factors_known IN (FALSE, TRUE)),
    CONSTRAINT ck_case_psu_length CHECK (max_psu_length_mm IS NULL OR max_psu_length_mm > 0),
    CONSTRAINT ck_case_gpu_length CHECK (max_gpu_length_mm IS NULL OR max_gpu_length_mm > 0),
    CONSTRAINT ck_case_gpu_height CHECK (max_gpu_height_mm IS NULL OR max_gpu_height_mm > 0),
    CONSTRAINT ck_case_gpu_thickness CHECK (max_gpu_thickness_mm IS NULL OR max_gpu_thickness_mm > 0),
    CONSTRAINT ck_case_slots CHECK (expansion_slot_count IS NULL OR expansion_slot_count > 0),
    CONSTRAINT ck_case_gpu_conditions CHECK (gpu_clearance_conditions IS NULL OR CHAR_LENGTH(TRIM(gpu_clearance_conditions)) > 0),
    CONSTRAINT ck_case_psu_conditions CHECK (psu_clearance_conditions IS NULL OR CHAR_LENGTH(TRIM(psu_clearance_conditions)) > 0),
    CONSTRAINT ck_case_gpu_unconditional CHECK (gpu_clearance_unconditional IN (FALSE, TRUE)
        AND (gpu_clearance_unconditional = FALSE OR max_gpu_length_mm IS NOT NULL
            OR max_gpu_height_mm IS NOT NULL OR max_gpu_thickness_mm IS NOT NULL)),
    CONSTRAINT ck_case_psu_unconditional CHECK (psu_clearance_unconditional IN (FALSE, TRUE)
        AND (psu_clearance_unconditional = FALSE OR max_psu_length_mm IS NOT NULL))
);

CREATE TABLE case_motherboard_form_factor (
    product_id VARCHAR(128) NOT NULL,
    form_factor VARCHAR(32) NOT NULL,
    PRIMARY KEY (product_id, form_factor),
    CONSTRAINT fk_case_board_form_spec FOREIGN KEY (product_id) REFERENCES case_spec(product_id),
    CONSTRAINT ck_case_board_form CHECK (form_factor IN ('ATX', 'MICRO_ATX', 'MINI_ITX', 'MINI_DTX', 'E_ATX'))
);

CREATE TABLE case_psu_form_factor (
    product_id VARCHAR(128) NOT NULL,
    form_factor VARCHAR(32) NOT NULL,
    PRIMARY KEY (product_id, form_factor),
    CONSTRAINT fk_case_psu_form_spec FOREIGN KEY (product_id) REFERENCES case_spec(product_id),
    CONSTRAINT ck_case_psu_form CHECK (form_factor IN ('ATX', 'SFX', 'SFX_L'))
);

-- known=FALSE의 빈 목록은 미확인, known=TRUE의 빈 목록은 확인된 지원 없음이다.
-- 부모·자식 행을 함께 보는 known/list 모순과 상품 종류 불일치는 등록 서비스가 거절한다.
