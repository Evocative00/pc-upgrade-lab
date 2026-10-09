-- 설치 모델·정확 제품·판매 구성을 구분한다. 기존 제품 UUID와 PC 제품 연결은 그대로 보존한다.
CREATE TABLE catalog_model (
    id VARCHAR(128) NOT NULL PRIMARY KEY,
    canonical_id VARCHAR(36) NOT NULL,
    type VARCHAR(20) NOT NULL,
    manufacturer VARCHAR(100) NOT NULL,
    model_name VARCHAR(255) NOT NULL,
    kind VARCHAR(32) NOT NULL,
    role VARCHAR(32) NOT NULL DEFAULT 'UNASSIGNED',
    verification_status VARCHAR(20) NOT NULL DEFAULT 'UNVERIFIED',
    family VARCHAR(100),
    series VARCHAR(128),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uq_catalog_model_canonical UNIQUE (canonical_id),
    CONSTRAINT uq_catalog_model_type UNIQUE (id, type),
    CONSTRAINT ck_catalog_model_id CHECK (CHAR_LENGTH(TRIM(id)) > 0),
    CONSTRAINT ck_catalog_model_canonical CHECK (CHAR_LENGTH(TRIM(canonical_id)) = 36),
    CONSTRAINT ck_catalog_model_manufacturer CHECK (CHAR_LENGTH(TRIM(manufacturer)) > 0),
    CONSTRAINT ck_catalog_model_name CHECK (CHAR_LENGTH(TRIM(model_name)) > 0),
    CONSTRAINT ck_catalog_model_kind CHECK (
        (kind = 'CPU_MODEL' AND type = 'CPU')
        OR (kind = 'GPU_CHIP_MODEL' AND type = 'GPU')
        OR (kind = 'BOARD_MODEL' AND type = 'MOTHERBOARD')
        OR (kind IN ('RAM_MODULE_MODEL', 'RAM_SPEC_GROUP') AND type = 'RAM')
        OR (kind = 'STORAGE_MODEL' AND type = 'STORAGE')
    ),
    CONSTRAINT ck_catalog_model_role CHECK (role IN ('UNASSIGNED', 'INSTALLED_PC_REFERENCE', 'PURCHASE_CANDIDATE', 'BOTH')),
    CONSTRAINT ck_catalog_model_verification CHECK (verification_status IN ('UNVERIFIED', 'PARTIAL', 'CORE_VERIFIED')),
    CONSTRAINT ck_catalog_model_family CHECK (family IS NULL OR CHAR_LENGTH(TRIM(family)) > 0),
    CONSTRAINT ck_catalog_model_series CHECK (series IS NULL OR CHAR_LENGTH(TRIM(series)) > 0)
);
CREATE INDEX idx_catalog_model_search ON catalog_model(type, manufacturer, model_name);

-- 모델 근거는 제품 근거와 별도 소유 관계다. 제품을 만들지 않고도 범위 수준의 근거를 보관한다.
CREATE TABLE catalog_model_source (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    model_id VARCHAR(128) NOT NULL,
    source_name VARCHAR(32) NOT NULL,
    external_id VARCHAR(128),
    source_revision VARCHAR(64),
    source_url VARCHAR(2048) NOT NULL,
    raw_payload JSON,
    retrieved_at TIMESTAMP(6) NOT NULL,
    review_scope VARCHAR(1000) NOT NULL,
    CONSTRAINT fk_catalog_model_source_model FOREIGN KEY (model_id) REFERENCES catalog_model(id),
    CONSTRAINT uq_catalog_model_source_external UNIQUE (source_name, external_id),
    CONSTRAINT uq_catalog_model_source_owner UNIQUE (model_id, id),
    CONSTRAINT ck_catalog_model_source_name CHECK (source_name IN ('BUILDCORES', 'MANUFACTURER', 'MANUAL')),
    CONSTRAINT ck_catalog_model_source_external CHECK (external_id IS NULL OR CHAR_LENGTH(TRIM(external_id)) > 0),
    CONSTRAINT ck_catalog_model_source_revision CHECK (source_revision IS NULL OR CHAR_LENGTH(TRIM(source_revision)) > 0),
    CONSTRAINT ck_catalog_model_source_url CHECK (CHAR_LENGTH(TRIM(source_url)) > 0),
    CONSTRAINT ck_catalog_model_source_scope CHECK (CHAR_LENGTH(TRIM(review_scope)) > 0),
    CONSTRAINT ck_catalog_model_source_buildcores CHECK (source_name <> 'BUILDCORES'
        OR (external_id IS NOT NULL AND source_revision IS NOT NULL AND raw_payload IS NOT NULL))
);
CREATE INDEX idx_catalog_model_source_model ON catalog_model_source(model_id);

CREATE TABLE catalog_model_alias (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    model_id VARCHAR(128) NOT NULL,
    raw_alias VARCHAR(500) NOT NULL,
    normalized_alias VARCHAR(500) NOT NULL,
    evidence_source_id BIGINT NOT NULL,
    CONSTRAINT fk_catalog_model_alias_source FOREIGN KEY (model_id, evidence_source_id)
        REFERENCES catalog_model_source(model_id, id),
    CONSTRAINT uq_catalog_model_alias UNIQUE (model_id, normalized_alias, evidence_source_id),
    CONSTRAINT ck_catalog_model_alias_raw CHECK (CHAR_LENGTH(TRIM(raw_alias)) > 0),
    CONSTRAINT ck_catalog_model_alias_normalized CHECK (CHAR_LENGTH(TRIM(normalized_alias)) > 0)
);
CREATE INDEX idx_catalog_model_alias_model ON catalog_model_alias(model_id);

ALTER TABLE catalog_product ADD COLUMN canonical_id VARCHAR(36);
ALTER TABLE catalog_product ADD COLUMN model_id VARCHAR(128);
ALTER TABLE catalog_product ADD COLUMN identity_kind VARCHAR(32) NOT NULL DEFAULT 'LEGACY_UNCLASSIFIED';
ALTER TABLE catalog_product ADD COLUMN role VARCHAR(32) NOT NULL DEFAULT 'UNASSIGNED';
ALTER TABLE catalog_product ADD COLUMN identity_evidence_source_id BIGINT;
ALTER TABLE catalog_product ADD COLUMN identity_review_scope VARCHAR(1000);
ALTER TABLE catalog_product ADD COLUMN identity_reviewed_at TIMESTAMP(6);
ALTER TABLE catalog_product ADD CONSTRAINT uq_catalog_product_canonical UNIQUE (canonical_id);
ALTER TABLE catalog_product ADD CONSTRAINT fk_catalog_product_model FOREIGN KEY (model_id, type) REFERENCES catalog_model(id, type);
ALTER TABLE catalog_product ADD CONSTRAINT fk_catalog_product_identity_evidence FOREIGN KEY (id, identity_evidence_source_id)
    REFERENCES catalog_product_source(product_id, id);
ALTER TABLE catalog_product ADD CONSTRAINT ck_catalog_product_canonical CHECK (canonical_id IS NULL OR CHAR_LENGTH(TRIM(canonical_id)) = 36);
ALTER TABLE catalog_product ADD CONSTRAINT ck_catalog_product_identity_kind CHECK (identity_kind IN ('LEGACY_UNCLASSIFIED', 'MODEL_REFERENCE', 'PHYSICAL_VARIANT', 'RETAIL_KIT'));
ALTER TABLE catalog_product ADD CONSTRAINT ck_catalog_product_role CHECK (role IN ('UNASSIGNED', 'INSTALLED_PC_REFERENCE', 'PURCHASE_CANDIDATE', 'BOTH'));
ALTER TABLE catalog_product ADD CONSTRAINT ck_catalog_product_kit_type CHECK (identity_kind <> 'RETAIL_KIT' OR type = 'RAM');
ALTER TABLE catalog_product ADD CONSTRAINT ck_catalog_product_model_reference CHECK (identity_kind <> 'MODEL_REFERENCE' OR model_id IS NOT NULL);
ALTER TABLE catalog_product ADD CONSTRAINT ck_catalog_product_legacy_identity CHECK (identity_kind <> 'LEGACY_UNCLASSIFIED'
    OR (model_id IS NULL AND role = 'UNASSIGNED'));
ALTER TABLE catalog_product ADD CONSTRAINT ck_catalog_product_identity_review CHECK (
    (identity_evidence_source_id IS NULL AND identity_review_scope IS NULL AND identity_reviewed_at IS NULL
        AND canonical_id IS NULL AND model_id IS NULL AND identity_kind = 'LEGACY_UNCLASSIFIED' AND role = 'UNASSIGNED')
    OR (identity_evidence_source_id IS NOT NULL AND identity_review_scope IS NOT NULL
        AND CHAR_LENGTH(TRIM(identity_review_scope)) > 0 AND identity_reviewed_at IS NOT NULL AND canonical_id IS NOT NULL)
);
CREATE INDEX idx_catalog_product_model ON catalog_product(model_id);

-- 기존 placeholder 제품 ID에는 새 FK를 붙이지 않는다. 신규 모델 참조에는 종류까지 검사한다.
ALTER TABLE pc_part ADD COLUMN catalog_model_id VARCHAR(128);
ALTER TABLE pc_part ADD COLUMN recognition_level VARCHAR(32);
ALTER TABLE pc_part ADD CONSTRAINT fk_pc_part_model FOREIGN KEY (catalog_model_id, type) REFERENCES catalog_model(id, type);
ALTER TABLE pc_part ADD CONSTRAINT ck_pc_part_recognition CHECK (
    (catalog_model_id IS NULL AND recognition_level IS NULL)
    OR (catalog_model_id IS NOT NULL AND recognition_level IS NOT NULL AND recognition_level IN ('SPEC_GROUP', 'MODEL'))
    OR (recognition_level IS NOT NULL AND recognition_level = 'PHYSICAL_VARIANT'
        AND match_status = 'MATCHED' AND catalog_product_id IS NOT NULL)
);
