-- 공용 제품 하나에 종류별 제원 한 행을 연결한다.
-- FK는 제품 존재만 보장한다. 제품 종류 일치/다른 종류 제원의 중복 연결은
-- 이후 적재·저장 서비스에서 트랜잭션으로 검사해야 한다.
-- 미확인 값은 NULL이며, 확인된 수치만 양수로 저장한다.
CREATE TABLE cpu_spec (
    product_id VARCHAR(128) NOT NULL PRIMARY KEY,
    socket_code VARCHAR(32),
    core_count SMALLINT,
    thread_count SMALLINT,
    base_clock_mhz INTEGER,
    boost_clock_mhz INTEGER,
    tdp_w DECIMAL(8,2),
    has_integrated_graphics BOOLEAN,
    integrated_graphics_model VARCHAR(128),
    CONSTRAINT fk_cpu_spec_product FOREIGN KEY (product_id) REFERENCES catalog_product(id),
    CONSTRAINT ck_cpu_socket CHECK (socket_code IS NULL OR CHAR_LENGTH(TRIM(socket_code)) > 0),
    CONSTRAINT ck_cpu_cores CHECK (core_count IS NULL OR core_count > 0),
    CONSTRAINT ck_cpu_threads CHECK (thread_count IS NULL OR thread_count > 0),
    CONSTRAINT ck_cpu_base_clock CHECK (base_clock_mhz IS NULL OR base_clock_mhz > 0),
    CONSTRAINT ck_cpu_boost_clock CHECK (boost_clock_mhz IS NULL OR boost_clock_mhz > 0),
    CONSTRAINT ck_cpu_tdp CHECK (tdp_w IS NULL OR tdp_w > 0),
    CONSTRAINT ck_cpu_igpu_boolean CHECK (
        has_integrated_graphics IS NULL OR has_integrated_graphics IN (FALSE, TRUE)
    ),
    CONSTRAINT ck_cpu_igpu_name CHECK (
        integrated_graphics_model IS NULL OR CHAR_LENGTH(TRIM(integrated_graphics_model)) > 0
    ),
    -- 이름이 있어도 공식 확인 전에는 유무를 NULL로 둘 수 있다. 없음+이름은 모순이다.
    CONSTRAINT ck_cpu_igpu_consistency CHECK (
        has_integrated_graphics IS NULL OR has_integrated_graphics = TRUE
        OR integrated_graphics_model IS NULL
    )
);

-- 초기 적재 대상은 한 종류의 DDR 규격을 쓰는 일반 데스크톱 메인보드다.
-- 여러 DDR 규격의 슬롯이 섞인 보드는 추후 관계 구조를 확장한 뒤 적재한다.
CREATE TABLE motherboard_spec (
    product_id VARCHAR(128) NOT NULL PRIMARY KEY,
    socket_code VARCHAR(32),
    chipset VARCHAR(64),
    form_factor VARCHAR(32),
    memory_type VARCHAR(10),
    memory_form_factor VARCHAR(16),
    memory_slot_count SMALLINT,
    max_memory_bytes BIGINT,
    supports_ecc BOOLEAN,
    CONSTRAINT fk_motherboard_spec_product FOREIGN KEY (product_id) REFERENCES catalog_product(id),
    CONSTRAINT ck_mb_socket CHECK (socket_code IS NULL OR CHAR_LENGTH(TRIM(socket_code)) > 0),
    CONSTRAINT ck_mb_chipset CHECK (chipset IS NULL OR CHAR_LENGTH(TRIM(chipset)) > 0),
    CONSTRAINT ck_mb_form_factor CHECK (form_factor IS NULL OR CHAR_LENGTH(TRIM(form_factor)) > 0),
    CONSTRAINT ck_mb_memory_type CHECK (memory_type IS NULL OR CHAR_LENGTH(TRIM(memory_type)) > 0),
    CONSTRAINT ck_mb_memory_form CHECK (
        memory_form_factor IS NULL OR CHAR_LENGTH(TRIM(memory_form_factor)) > 0
    ),
    CONSTRAINT ck_mb_memory_slots CHECK (memory_slot_count IS NULL OR memory_slot_count > 0),
    CONSTRAINT ck_mb_memory_capacity CHECK (max_memory_bytes IS NULL OR max_memory_bytes > 0),
    CONSTRAINT ck_mb_ecc_boolean CHECK (supports_ecc IS NULL OR supports_ecc IN (FALSE, TRUE))
);

CREATE TABLE ram_spec (
    product_id VARCHAR(128) NOT NULL PRIMARY KEY,
    memory_type VARCHAR(10),
    -- 모듈 한 장 용량(bytes)과 제품 구성의 장수를 분리한다. 총량은 두 값의 곱이다.
    module_capacity_bytes BIGINT,
    module_count SMALLINT,
    -- 제조사 확인한 전송률(MT/s). 자동 인식의 원문 MHz 값을 덮어쓰지 않는다.
    data_rate_mts INTEGER,
    module_form_factor VARCHAR(16),
    pin_count SMALLINT,
    is_ecc BOOLEAN,
    buffer_type VARCHAR(16),
    voltage_v DECIMAL(5,3),
    height_mm DECIMAL(7,2),
    CONSTRAINT fk_ram_spec_product FOREIGN KEY (product_id) REFERENCES catalog_product(id),
    CONSTRAINT ck_ram_memory_type CHECK (memory_type IS NULL OR CHAR_LENGTH(TRIM(memory_type)) > 0),
    CONSTRAINT ck_ram_capacity CHECK (module_capacity_bytes IS NULL OR module_capacity_bytes > 0),
    CONSTRAINT ck_ram_module_count CHECK (module_count IS NULL OR module_count > 0),
    CONSTRAINT ck_ram_data_rate CHECK (data_rate_mts IS NULL OR data_rate_mts > 0),
    CONSTRAINT ck_ram_form_factor CHECK (
        module_form_factor IS NULL OR CHAR_LENGTH(TRIM(module_form_factor)) > 0
    ),
    CONSTRAINT ck_ram_pins CHECK (pin_count IS NULL OR pin_count > 0),
    CONSTRAINT ck_ram_ecc_boolean CHECK (is_ecc IS NULL OR is_ecc IN (FALSE, TRUE)),
    CONSTRAINT ck_ram_buffer_type CHECK (
        buffer_type IS NULL OR buffer_type IN ('UNBUFFERED', 'REGISTERED', 'LOAD_REDUCED')
    ),
    CONSTRAINT ck_ram_voltage CHECK (voltage_v IS NULL OR voltage_v > 0),
    CONSTRAINT ck_ram_height CHECK (height_mm IS NULL OR height_mm > 0)
);

-- 소켓/메모리 규격 정규화와 제조사 검증은 후속 적재 서비스의 책임이다.
-- 소켓 일치만으로 BIOS/CPU 지원을, supports_ecc만으로 RDIMM 지원을 확정하지 않는다.
-- CPU의 복수 지원 메모리 종류는 단일 문자열로 줄이지 않고 후속 관계 테이블로 다룬다.
-- RAM 모듈 수는 pc_part.quantity(장착 장치 수) 또는 구매할 키트 수와 다른 값이다.
