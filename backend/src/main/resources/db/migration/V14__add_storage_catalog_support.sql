-- Structure only: no products, prices, compatibility approvals or startup data imports.
-- Capacities are manufacturer decimal nominal values, not scan/filesystem capacities.
CREATE TABLE storage_spec (
    product_id VARCHAR(128) NOT NULL PRIMARY KEY,
    storage_kind VARCHAR(32),
    capacity_bytes BIGINT,
    advertised_capacity_gb DECIMAL(12,3),
    capacity_basis VARCHAR(32),
    form_factor VARCHAR(32),
    bus_interface VARCHAR(32),
    interface_protocol VARCHAR(32),
    pcie_version VARCHAR(16),
    pcie_lanes INTEGER,
    nvme_version VARCHAR(16),
    sata_version VARCHAR(16),
    connector_key VARCHAR(32),
    m2_length_code VARCHAR(32),
    length_mm DECIMAL(8,2),
    width_mm DECIMAL(8,2),
    height_mm DECIMAL(8,2),
    dimensions_basis VARCHAR(32),
    heatsink_included BOOLEAN,
    CONSTRAINT fk_storage_product FOREIGN KEY (product_id) REFERENCES catalog_product(id),
    CONSTRAINT ck_storage_kind CHECK (storage_kind IS NULL OR storage_kind IN ('SSD', 'HDD')),
    CONSTRAINT ck_storage_capacity_positive CHECK (capacity_bytes IS NULL OR capacity_bytes > 0),
    CONSTRAINT ck_storage_advertised_positive CHECK (advertised_capacity_gb IS NULL OR advertised_capacity_gb > 0),
    CONSTRAINT ck_storage_capacity_basis CHECK (
        (capacity_bytes IS NULL AND advertised_capacity_gb IS NULL AND capacity_basis IS NULL)
        OR ((capacity_bytes IS NOT NULL OR advertised_capacity_gb IS NOT NULL)
            AND capacity_basis IS NOT NULL AND capacity_basis = 'DECIMAL_GB')
    ),
    CONSTRAINT ck_storage_capacity_consistent CHECK (capacity_bytes IS NULL OR advertised_capacity_gb IS NULL
        OR capacity_bytes = advertised_capacity_gb * 1000000000),
    CONSTRAINT ck_storage_form CHECK (form_factor IS NULL OR form_factor IN ('TWO_POINT_FIVE_INCH', 'THREE_POINT_FIVE_INCH', 'M2')),
    CONSTRAINT ck_storage_bus CHECK (bus_interface IS NULL OR bus_interface IN ('SATA', 'PCIE')),
    CONSTRAINT ck_storage_protocol CHECK (interface_protocol IS NULL OR interface_protocol IN ('ATA', 'NVME')),
    CONSTRAINT ck_storage_lanes CHECK (pcie_lanes IS NULL OR pcie_lanes IN (1, 2, 4, 8, 16, 32)),
    CONSTRAINT ck_storage_pcie_bus CHECK ((pcie_version IS NULL AND pcie_lanes IS NULL)
        OR (bus_interface IS NOT NULL AND bus_interface = 'PCIE')),
    CONSTRAINT ck_storage_sata_values CHECK (bus_interface IS NULL OR bus_interface <> 'SATA'
        OR (pcie_version IS NULL AND pcie_lanes IS NULL AND nvme_version IS NULL
            AND (interface_protocol IS NULL OR interface_protocol = 'ATA'))),
    CONSTRAINT ck_storage_pcie_values CHECK (bus_interface IS NULL OR bus_interface <> 'PCIE'
        OR (sata_version IS NULL AND (interface_protocol IS NULL OR interface_protocol = 'NVME'))),
    CONSTRAINT ck_storage_nvme_bus CHECK (interface_protocol IS NULL OR interface_protocol <> 'NVME'
        OR (bus_interface IS NOT NULL AND bus_interface = 'PCIE')),
    CONSTRAINT ck_storage_nvme_version CHECK (nvme_version IS NULL
        OR (interface_protocol IS NOT NULL AND interface_protocol = 'NVME')),
    CONSTRAINT ck_storage_sata_version CHECK (sata_version IS NULL
        OR (bus_interface IS NOT NULL AND bus_interface = 'SATA')),
    CONSTRAINT ck_storage_connector_key CHECK (connector_key IS NULL OR connector_key IN ('B', 'M', 'B_M')),
    CONSTRAINT ck_storage_m2_code CHECK (m2_length_code IS NULL OR m2_length_code IN ('2230', '2242', '2260', '2280', '22110')),
    CONSTRAINT ck_storage_m2_form CHECK ((connector_key IS NULL AND m2_length_code IS NULL)
        OR (form_factor IS NOT NULL AND form_factor = 'M2')),
    CONSTRAINT ck_storage_length CHECK (length_mm IS NULL OR length_mm > 0),
    CONSTRAINT ck_storage_width CHECK (width_mm IS NULL OR width_mm > 0),
    CONSTRAINT ck_storage_height CHECK (height_mm IS NULL OR height_mm > 0),
    CONSTRAINT ck_storage_dimension_basis CHECK (
        (length_mm IS NULL AND width_mm IS NULL AND height_mm IS NULL AND dimensions_basis IS NULL)
        OR ((length_mm IS NOT NULL OR width_mm IS NOT NULL OR height_mm IS NOT NULL)
            AND dimensions_basis IS NOT NULL AND dimensions_basis IN ('NOMINAL', 'MANUFACTURER_MAXIMUM', 'PUBLISHED'))
    ),
    CONSTRAINT ck_storage_heatsink CHECK (heatsink_included IS NULL OR heatsink_included IN (FALSE, TRUE))
);

CREATE TABLE motherboard_storage_profile (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    motherboard_product_id VARCHAR(128) NOT NULL,
    revision_key VARCHAR(64) NOT NULL,
    revision_scope VARCHAR(16) NOT NULL,
    hardware_revision VARCHAR(48),
    complete_data_known BOOLEAN NOT NULL DEFAULT FALSE,
    conditions VARCHAR(2000),
    CONSTRAINT fk_storage_profile_board FOREIGN KEY (motherboard_product_id) REFERENCES motherboard_spec(product_id),
    CONSTRAINT uq_storage_profile_revision UNIQUE (motherboard_product_id, revision_key),
    CONSTRAINT ck_storage_profile_revision CHECK (
        (revision_scope = 'MODEL' AND revision_key = 'MODEL' AND hardware_revision IS NULL)
        OR (revision_scope = 'EXACT' AND hardware_revision IS NOT NULL
            AND CHAR_LENGTH(TRIM(hardware_revision)) > 0 AND revision_key = CONCAT('REV:', hardware_revision))
    ),
    CONSTRAINT ck_storage_profile_complete CHECK (complete_data_known IN (FALSE, TRUE))
);

CREATE TABLE motherboard_storage_slot (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    profile_id BIGINT NOT NULL,
    slot_key VARCHAR(64) NOT NULL,
    connector_type VARCHAR(32) NOT NULL,
    connector_key VARCHAR(32),
    max_pcie_version VARCHAR(16),
    max_pcie_lanes INTEGER,
    sata_version VARCHAR(16),
    lane_source VARCHAR(32),
    nvme_boot_support BOOLEAN,
    notes VARCHAR(2000),
    CONSTRAINT fk_storage_slot_profile FOREIGN KEY (profile_id) REFERENCES motherboard_storage_profile(id),
    CONSTRAINT uq_storage_slot_key UNIQUE (profile_id, slot_key),
    CONSTRAINT ck_storage_slot_key CHECK (CHAR_LENGTH(TRIM(slot_key)) > 0),
    CONSTRAINT ck_storage_slot_connector CHECK (connector_type IN ('M2', 'SATA')),
    CONSTRAINT ck_storage_slot_key_type CHECK (connector_key IS NULL OR connector_key IN ('B', 'M', 'B_M')),
    CONSTRAINT ck_storage_slot_lanes CHECK (max_pcie_lanes IS NULL OR max_pcie_lanes IN (1, 2, 4, 8, 16, 32)),
    CONSTRAINT ck_storage_slot_lane_source CHECK (lane_source IS NULL OR lane_source IN ('CPU', 'CHIPSET')),
    CONSTRAINT ck_storage_slot_sata CHECK (connector_type <> 'SATA'
        OR (connector_key IS NULL AND max_pcie_version IS NULL AND max_pcie_lanes IS NULL AND nvme_boot_support IS NULL)),
    CONSTRAINT ck_storage_slot_nvme_boot CHECK (nvme_boot_support IS NULL OR nvme_boot_support IN (FALSE, TRUE))
);

CREATE TABLE motherboard_storage_slot_length (
    slot_id BIGINT NOT NULL,
    length_code VARCHAR(32) NOT NULL,
    CONSTRAINT pk_storage_slot_length PRIMARY KEY (slot_id, length_code),
    CONSTRAINT fk_storage_length_slot FOREIGN KEY (slot_id) REFERENCES motherboard_storage_slot(id),
    CONSTRAINT ck_storage_length_code CHECK (length_code IN ('2230', '2242', '2260', '2280', '22110'))
);

CREATE TABLE motherboard_storage_slot_bus (
    slot_id BIGINT NOT NULL,
    bus_interface VARCHAR(32) NOT NULL,
    CONSTRAINT pk_storage_slot_bus PRIMARY KEY (slot_id, bus_interface),
    CONSTRAINT fk_storage_bus_slot FOREIGN KEY (slot_id) REFERENCES motherboard_storage_slot(id),
    CONSTRAINT ck_storage_slot_bus CHECK (bus_interface IN ('SATA', 'PCIE'))
);

CREATE TABLE motherboard_storage_slot_protocol (
    slot_id BIGINT NOT NULL,
    interface_protocol VARCHAR(32) NOT NULL,
    CONSTRAINT pk_storage_slot_protocol PRIMARY KEY (slot_id, interface_protocol),
    CONSTRAINT fk_storage_protocol_slot FOREIGN KEY (slot_id) REFERENCES motherboard_storage_slot(id),
    CONSTRAINT ck_storage_slot_protocol CHECK (interface_protocol IN ('ATA', 'NVME'))
);

CREATE TABLE motherboard_storage_rule (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    slot_id BIGINT NOT NULL,
    rule_key VARCHAR(64) NOT NULL,
    cpu_condition VARCHAR(1000),
    bios_condition VARCHAR(1000),
    effect VARCHAR(32) NOT NULL,
    affected_slot_key VARCHAR(64),
    raw_condition VARCHAR(4000) NOT NULL,
    CONSTRAINT fk_storage_rule_slot FOREIGN KEY (slot_id) REFERENCES motherboard_storage_slot(id),
    CONSTRAINT uq_storage_rule_key UNIQUE (slot_id, rule_key),
    CONSTRAINT ck_storage_rule_key CHECK (CHAR_LENGTH(TRIM(rule_key)) > 0),
    CONSTRAINT ck_storage_rule_effect CHECK (effect IN ('DISABLED', 'LANES_REDUCED', 'PORT_SHARED', 'CONDITIONAL_SUPPORT', 'UNSTRUCTURED')),
    CONSTRAINT ck_storage_raw_condition CHECK (CHAR_LENGTH(TRIM(raw_condition)) > 0)
);

CREATE TABLE motherboard_storage_source (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    profile_id BIGINT NOT NULL,
    source_url VARCHAR(2048) NOT NULL,
    checked_at TIMESTAMP(6) NOT NULL,
    source_revision VARCHAR(255),
    document_location VARCHAR(255),
    supported_facts VARCHAR(4000) NOT NULL,
    CONSTRAINT fk_storage_source_profile FOREIGN KEY (profile_id) REFERENCES motherboard_storage_profile(id),
    CONSTRAINT ck_storage_source_url CHECK (CHAR_LENGTH(TRIM(source_url)) > 0),
    CONSTRAINT ck_storage_source_facts CHECK (CHAR_LENGTH(TRIM(supported_facts)) > 0)
);

-- Type agreement and child-list consistency are checked by the transactional service/value objects.
-- Empty lists and complete_data_known=FALSE are unknown/partial data, never an incompatibility verdict.
