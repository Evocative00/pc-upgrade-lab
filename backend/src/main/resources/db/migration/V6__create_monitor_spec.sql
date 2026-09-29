-- 공용 제품·가격·출처를 재사용한다. 제품 제원과 사용자 PC의 현재 출력 설정을 구분한다.
-- 확인하지 못한 값은 NULL로 보존한다. 이 마이그레이션은 제품 데이터를 등록하지 않는다.
CREATE TABLE monitor_spec (
    product_id VARCHAR(128) NOT NULL PRIMARY KEY,
    screen_size_inches DECIMAL(5,2),
    native_width_px INTEGER,
    native_height_px INTEGER,
    panel_type VARCHAR(16),
    -- 모두 기본 해상도에서의 최대값이며, 실제 사용 가능 여부는 연결 조건에도 달려 있다.
    native_standard_refresh_hz DECIMAL(7,3),
    has_refresh_overclock BOOLEAN,
    native_oc_refresh_hz DECIMAL(7,3),
    -- 사용 중 전력과 기준·측정 조건을 함께 기록한다. PC 본체의 PSU 요구량에 합산하지 않는다.
    active_power_w DECIMAL(8,2),
    active_power_basis VARCHAR(64),
    active_power_conditions VARCHAR(1000),
    CONSTRAINT fk_monitor_spec_product FOREIGN KEY (product_id) REFERENCES catalog_product(id),
    CONSTRAINT ck_monitor_screen_size CHECK (screen_size_inches IS NULL OR screen_size_inches > 0),
    CONSTRAINT ck_monitor_native_width CHECK (native_width_px IS NULL OR native_width_px > 0),
    CONSTRAINT ck_monitor_native_height CHECK (native_height_px IS NULL OR native_height_px > 0),
    CONSTRAINT ck_monitor_resolution_pair CHECK (
        (native_width_px IS NULL AND native_height_px IS NULL)
        OR (native_width_px IS NOT NULL AND native_height_px IS NOT NULL)
    ),
    CONSTRAINT ck_monitor_panel_type CHECK (
        panel_type IS NULL OR panel_type IN ('IPS', 'VA', 'TN', 'OLED')
    ),
    CONSTRAINT ck_monitor_standard_refresh CHECK (
        native_standard_refresh_hz IS NULL OR native_standard_refresh_hz > 0
    ),
    CONSTRAINT ck_monitor_oc_boolean CHECK (
        has_refresh_overclock IS NULL OR has_refresh_overclock IN (FALSE, TRUE)
    ),
    CONSTRAINT ck_monitor_oc_refresh CHECK (
        native_oc_refresh_hz IS NULL OR native_oc_refresh_hz > 0
    ),
    -- OC 미기재를 미지원으로 추정하지 않는다. TRUE + OC Hz NULL은 최대값만 미확인인 경우다.
    -- CHECK는 UNKNOWN도 통과시키므로 TRUE 여부 검사에 IS NOT NULL을 함께 둔다.
    CONSTRAINT ck_monitor_oc_support CHECK (
        native_oc_refresh_hz IS NULL
        OR (has_refresh_overclock IS NOT NULL AND has_refresh_overclock = TRUE)
    ),
    CONSTRAINT ck_monitor_refresh_order CHECK (
        native_standard_refresh_hz IS NULL OR native_oc_refresh_hz IS NULL
        OR native_oc_refresh_hz > native_standard_refresh_hz
    ),
    CONSTRAINT ck_monitor_active_power CHECK (active_power_w IS NULL OR active_power_w > 0),
    CONSTRAINT ck_monitor_power_basis CHECK (
        active_power_basis IS NULL OR active_power_basis IN ('MANUFACTURER_TYPICAL', 'MEASURED_ACTIVE')
    ),
    CONSTRAINT ck_monitor_power_conditions CHECK (
        active_power_conditions IS NULL OR CHAR_LENGTH(TRIM(active_power_conditions)) > 0
    ),
    CONSTRAINT ck_monitor_power_evidence CHECK (
        (active_power_w IS NULL AND active_power_basis IS NULL AND active_power_conditions IS NULL)
        OR (active_power_w IS NOT NULL AND active_power_basis IS NOT NULL AND active_power_conditions IS NOT NULL)
    )
);

-- 제조사 typical 또는 사용 중 측정값이라도 조건을 확인하지 못했다면 전력 세 필드를 NULL로 둔다.
-- 원문 숫자·최대/대기 전력·어댑터 정격·지역·연결 조건은 기존 출처 payload에 보존한다.
-- 조건의 실제 근거는 적재 과정에서 검토한다. 문자열 존재만으로 사실이 검증되지는 않는다.
-- Nano IPS 등의 추가 기술은 출처에 보존한다. 패널 종류나 전력 기준을 추정하지 않는다.
-- 제품 종류 일치와 다른 종류 제원의 중복 연결은 기존 등록·조회 서비스에서 검사한다.
