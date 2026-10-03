-- 제조사 CPU 지원 목록의 일부를 보완한다. 소켓 일치/목록 부재만으로 호환 여부를 판정하지 않는다.
-- 동일 제품의 보드 리비전과 CPU 스테핑별 근거를 분리하고 BIOS 표기는 문자열로 보존한다.
CREATE TABLE motherboard_cpu_support_profile (
    motherboard_product_id VARCHAR(128) NOT NULL,
    revision_key VARCHAR(64) NOT NULL,
    revision_scope VARCHAR(16) NOT NULL,
    hardware_revision VARCHAR(48),
    conditions VARCHAR(2000) NOT NULL,
    evidence_source_id BIGINT NOT NULL,
    CONSTRAINT pk_mb_cpu_support_profile PRIMARY KEY (motherboard_product_id, revision_key),
    CONSTRAINT fk_mb_cpu_profile_board FOREIGN KEY (motherboard_product_id) REFERENCES motherboard_spec (product_id),
    CONSTRAINT fk_mb_cpu_profile_evidence FOREIGN KEY (motherboard_product_id, evidence_source_id)
        REFERENCES catalog_product_source (product_id, id),
    CONSTRAINT ck_mb_cpu_revision_scope CHECK (revision_scope IN ('MODEL', 'EXACT')),
    CONSTRAINT ck_mb_cpu_revision CHECK (
        (revision_scope = 'MODEL' AND revision_key = 'MODEL' AND hardware_revision IS NULL)
        OR (revision_scope = 'EXACT' AND hardware_revision IS NOT NULL
            AND CHAR_LENGTH(TRIM(hardware_revision)) > 0 AND revision_key = CONCAT('REV:', hardware_revision))
    ),
    CONSTRAINT ck_mb_cpu_profile_conditions CHECK (CHAR_LENGTH(TRIM(conditions)) > 0)
);

CREATE TABLE motherboard_cpu_support (
    motherboard_product_id VARCHAR(128) NOT NULL,
    revision_key VARCHAR(64) NOT NULL,
    cpu_product_id VARCHAR(128) NOT NULL,
    variant_key VARCHAR(64) NOT NULL,
    support_status VARCHAR(16) NOT NULL,
    reported_cpu_name VARCHAR(255) NOT NULL,
    cpu_stepping VARCHAR(64),
    bios_requirement VARCHAR(16) NOT NULL,
    minimum_bios_version VARCHAR(64),
    manufacturer_bios_label VARCHAR(255),
    source_url VARCHAR(2048) NOT NULL,
    conditions VARCHAR(2000) NOT NULL,
    CONSTRAINT pk_mb_cpu_support PRIMARY KEY (motherboard_product_id, revision_key, cpu_product_id, variant_key),
    CONSTRAINT fk_mb_cpu_support_profile FOREIGN KEY (motherboard_product_id, revision_key)
        REFERENCES motherboard_cpu_support_profile (motherboard_product_id, revision_key),
    CONSTRAINT fk_mb_cpu_support_cpu FOREIGN KEY (cpu_product_id) REFERENCES cpu_spec (product_id),
    CONSTRAINT ck_mb_cpu_variant CHECK (CHAR_LENGTH(TRIM(variant_key)) > 0),
    CONSTRAINT ck_mb_cpu_reported_name CHECK (CHAR_LENGTH(TRIM(reported_cpu_name)) > 0),
    CONSTRAINT ck_mb_cpu_stepping CHECK (cpu_stepping IS NULL OR CHAR_LENGTH(TRIM(cpu_stepping)) > 0),
    CONSTRAINT ck_mb_cpu_bios_label CHECK (manufacturer_bios_label IS NULL OR CHAR_LENGTH(TRIM(manufacturer_bios_label)) > 0),
    CONSTRAINT ck_mb_cpu_status CHECK (support_status IN ('LISTED', 'UNVERIFIED')),
    CONSTRAINT ck_mb_cpu_bios_kind CHECK (bios_requirement IN ('VERSION', 'ALL', 'UNKNOWN')),
    CONSTRAINT ck_mb_cpu_bios CHECK (
        (bios_requirement = 'VERSION' AND minimum_bios_version IS NOT NULL
            AND CHAR_LENGTH(TRIM(minimum_bios_version)) > 0 AND UPPER(TRIM(minimum_bios_version)) <> 'ALL'
            AND UPPER(TRIM(minimum_bios_version)) <> 'LATEST BETA BIOS' AND manufacturer_bios_label IS NOT NULL
            AND CHAR_LENGTH(TRIM(manufacturer_bios_label)) > 0)
        OR (bios_requirement = 'ALL' AND minimum_bios_version IS NULL
            AND manufacturer_bios_label IS NOT NULL AND UPPER(TRIM(manufacturer_bios_label)) = 'ALL')
        OR (bios_requirement = 'UNKNOWN' AND minimum_bios_version IS NULL)
    ),
    CONSTRAINT ck_mb_cpu_unverified CHECK (support_status = 'LISTED'
        OR (bios_requirement = 'UNKNOWN' AND manufacturer_bios_label IS NULL AND cpu_stepping IS NULL)),
    CONSTRAINT ck_mb_cpu_source_url CHECK (CHAR_LENGTH(TRIM(source_url)) > 0),
    CONSTRAINT ck_mb_cpu_conditions CHECK (CHAR_LENGTH(TRIM(conditions)) > 0)
);
