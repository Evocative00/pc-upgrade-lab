-- 기존 제품·가격·출처를 재사용한다. GPU 한 행은 정확한 데스크톱 외장 카드 제품 하나다.
-- 확인하지 못한 제원은 NULL이다. 데이터 등록만으로 제품의 검증/활성 상태를 바꾸지 않는다.
CREATE TABLE gpu_spec (
    product_id VARCHAR(128) NOT NULL PRIMARY KEY,
    chip_vendor VARCHAR(16),
    chipset VARCHAR(128),
    vram_bytes BIGINT,
    memory_type VARCHAR(16),
    pcie_version VARCHAR(16),
    -- 물리 단자가 x16이어도 실제 연결 레인은 x8일 수 있으므로 두 값을 분리한다.
    pcie_connector_lanes SMALLINT,
    pcie_active_lanes SMALLINT,
    length_mm DECIMAL(8,2),
    height_mm DECIMAL(8,2),
    thickness_mm DECIMAL(8,2),
    slot_width DECIMAL(4,2),
    -- 제조사의 카드 전력 값과 그 정의(TBP/TGP 등)를 함께 보존한다.
    card_power_w DECIMAL(8,2),
    card_power_basis VARCHAR(64),
    -- PC 전체에 대한 최소/권장 PSU 출력이다. 카드 소비전력과 다르다.
    psu_requirement_w INTEGER,
    psu_requirement_basis VARCHAR(16),
    power_connectors_known BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_gpu_spec_product FOREIGN KEY (product_id) REFERENCES catalog_product(id),
    CONSTRAINT ck_gpu_chip_vendor CHECK (
        chip_vendor IS NULL OR chip_vendor IN ('NVIDIA', 'AMD', 'INTEL')
    ),
    CONSTRAINT ck_gpu_chipset CHECK (chipset IS NULL OR CHAR_LENGTH(TRIM(chipset)) > 0),
    CONSTRAINT ck_gpu_vram CHECK (vram_bytes IS NULL OR vram_bytes > 0),
    CONSTRAINT ck_gpu_memory_type CHECK (
        memory_type IS NULL OR CHAR_LENGTH(TRIM(memory_type)) > 0
    ),
    CONSTRAINT ck_gpu_pcie_version CHECK (
        pcie_version IS NULL OR CHAR_LENGTH(TRIM(pcie_version)) > 0
    ),
    CONSTRAINT ck_gpu_pcie_connector CHECK (
        pcie_connector_lanes IS NULL OR pcie_connector_lanes IN (1, 2, 4, 8, 16, 32)
    ),
    CONSTRAINT ck_gpu_pcie_active CHECK (
        pcie_active_lanes IS NULL OR pcie_active_lanes IN (1, 2, 4, 8, 16, 32)
    ),
    CONSTRAINT ck_gpu_pcie_lane_order CHECK (
        pcie_connector_lanes IS NULL OR pcie_active_lanes IS NULL
        OR pcie_active_lanes <= pcie_connector_lanes
    ),
    CONSTRAINT ck_gpu_length CHECK (length_mm IS NULL OR length_mm > 0),
    CONSTRAINT ck_gpu_height CHECK (height_mm IS NULL OR height_mm > 0),
    CONSTRAINT ck_gpu_thickness CHECK (thickness_mm IS NULL OR thickness_mm > 0),
    CONSTRAINT ck_gpu_slot_width CHECK (slot_width IS NULL OR slot_width > 0),
    CONSTRAINT ck_gpu_card_power CHECK (card_power_w IS NULL OR card_power_w > 0),
    CONSTRAINT ck_gpu_card_power_basis CHECK (
        card_power_basis IS NULL OR CHAR_LENGTH(TRIM(card_power_basis)) > 0
    ),
    -- CHECK의 NULL 판정 누락을 피하도록 한쪽만 입력된 경우를 명시적으로 거절한다.
    CONSTRAINT ck_gpu_card_power_pair CHECK (
        (card_power_w IS NULL AND card_power_basis IS NULL)
        OR (card_power_w IS NOT NULL AND card_power_basis IS NOT NULL)
    ),
    CONSTRAINT ck_gpu_psu_requirement CHECK (
        psu_requirement_w IS NULL OR psu_requirement_w > 0
    ),
    CONSTRAINT ck_gpu_psu_basis CHECK (
        psu_requirement_basis IS NULL OR psu_requirement_basis IN ('MINIMUM', 'RECOMMENDED')
    ),
    CONSTRAINT ck_gpu_psu_pair CHECK (
        (psu_requirement_w IS NULL AND psu_requirement_basis IS NULL)
        OR (psu_requirement_w IS NOT NULL AND psu_requirement_basis IS NOT NULL)
    ),
    CONSTRAINT ck_gpu_connectors_known CHECK (power_connectors_known IN (FALSE, TRUE))
);

-- 보조전원 단자는 카드에 붙은 구성 값이다. 같은 종류는 한 행에 개수를 기록한다.
-- 6핀 1개 + 8핀 1개는 두 행, 8핀 2개는 한 행(count=2)이다.
CREATE TABLE gpu_power_connector (
    product_id VARCHAR(128) NOT NULL,
    connector_type VARCHAR(32) NOT NULL,
    connector_count SMALLINT NOT NULL,
    PRIMARY KEY (product_id, connector_type),
    CONSTRAINT fk_gpu_connector_spec FOREIGN KEY (product_id) REFERENCES gpu_spec(product_id),
    CONSTRAINT ck_gpu_connector_type CHECK (
        connector_type IN ('PCIE_6PIN', 'PCIE_8PIN', 'PCIE_16PIN_UNSPECIFIED',
            'PCIE_12VHPWR', 'PCIE_12V_2X6')
    ),
    CONSTRAINT ck_gpu_connector_count CHECK (connector_count > 0)
);

-- known=FALSE + 0행은 미확인, known=TRUE + 0행은 확인된 보조전원 없음이다.
-- known=FALSE인데 구성 행이 있는 모순은 여러 행을 함께 보는 등록 서비스가 거절한다.
-- 일반 16핀 표기를 12VHPWR/12V-2x6으로 추정하지 않는다.
-- 제품 종류 일치와 다른 종류 제원의 중복 연결도 기존 등록 서비스에서 검사한다.
