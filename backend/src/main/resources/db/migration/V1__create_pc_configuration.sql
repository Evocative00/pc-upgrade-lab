CREATE TABLE pc_configuration (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    owner_key VARCHAR(128) NOT NULL,
    name VARCHAR(100) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL
);
CREATE INDEX idx_pc_configuration_owner ON pc_configuration(owner_key);

CREATE TABLE pc_part (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    pc_id BIGINT NOT NULL,
    type VARCHAR(20) NOT NULL,
    display_name VARCHAR(255) NOT NULL,
    raw_name VARCHAR(500),
    quantity INTEGER NOT NULL,
    source VARCHAR(10) NOT NULL,
    catalog_product_id VARCHAR(128),
    match_status VARCHAR(20) NOT NULL,
    specs JSON NOT NULL,
    CONSTRAINT fk_pc_part_pc FOREIGN KEY (pc_id) REFERENCES pc_configuration(id),
    CONSTRAINT ck_pc_part_quantity CHECK (quantity BETWEEN 1 AND 64),
    CONSTRAINT ck_pc_part_type CHECK (type IN ('CPU','GPU','MOTHERBOARD','RAM','STORAGE','PSU','CASE','COOLER','MONITOR')),
    CONSTRAINT ck_pc_part_source CHECK (source IN ('AUTO','MANUAL')),
    CONSTRAINT ck_pc_part_match CHECK (
        (match_status = 'UNMATCHED' AND catalog_product_id IS NULL) OR
        (match_status = 'MATCHED' AND catalog_product_id IS NOT NULL)
    )
);
CREATE INDEX idx_pc_part_pc ON pc_part(pc_id);
