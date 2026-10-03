-- CPU 메모리 지원은 기존 cpu_spec/seed와 분리해 보완한다. 기존 제원/검토 상태/가격은 갱신하지 않는다.
-- 메모리 종류 목록의 완전성, 미확인 NULL, 제조사 공표 조건을 함께 보존한다.
ALTER TABLE catalog_product_source ADD CONSTRAINT uq_catalog_source_product_id UNIQUE (product_id, id);

CREATE TABLE cpu_memory_support (
    product_id VARCHAR(128) NOT NULL,
    memory_types_known BOOLEAN NOT NULL,
    max_memory_bytes BIGINT,
    channel_count SMALLINT,
    capacity_conditions VARCHAR(2000) NOT NULL,
    evidence_source_id BIGINT NOT NULL,
    CONSTRAINT pk_cpu_memory_support PRIMARY KEY (product_id),
    CONSTRAINT fk_cpu_memory_cpu FOREIGN KEY (product_id) REFERENCES cpu_spec (product_id),
    CONSTRAINT fk_cpu_memory_evidence FOREIGN KEY (product_id, evidence_source_id)
        REFERENCES catalog_product_source (product_id, id),
    CONSTRAINT ck_cpu_memory_capacity CHECK (max_memory_bytes IS NULL OR max_memory_bytes > 0),
    CONSTRAINT ck_cpu_memory_channels CHECK (channel_count IS NULL OR channel_count > 0),
    CONSTRAINT ck_cpu_memory_capacity_conditions CHECK (CHAR_LENGTH(TRIM(capacity_conditions)) > 0)
);

CREATE TABLE cpu_memory_type_support (
    product_id VARCHAR(128) NOT NULL,
    memory_type VARCHAR(8) NOT NULL,
    max_standard_data_rate_mts INTEGER,
    data_rate_conditions VARCHAR(2000) NOT NULL,
    CONSTRAINT pk_cpu_memory_type_support PRIMARY KEY (product_id, memory_type),
    CONSTRAINT fk_cpu_memory_type_profile FOREIGN KEY (product_id) REFERENCES cpu_memory_support (product_id),
    CONSTRAINT ck_cpu_memory_type CHECK (memory_type IN ('DDR4', 'DDR5')),
    CONSTRAINT ck_cpu_memory_data_rate CHECK (max_standard_data_rate_mts IS NULL OR max_standard_data_rate_mts > 0),
    CONSTRAINT ck_cpu_memory_rate_conditions CHECK (CHAR_LENGTH(TRIM(data_rate_conditions)) > 0)
);
