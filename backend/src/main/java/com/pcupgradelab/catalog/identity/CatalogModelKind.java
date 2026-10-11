package com.pcupgradelab.catalog.identity;

import com.pcupgradelab.pc.PartType;

public enum CatalogModelKind {
    CPU_MODEL(PartType.CPU), GPU_CHIP_MODEL(PartType.GPU), BOARD_MODEL(PartType.MOTHERBOARD),
    RAM_MODULE_MODEL(PartType.RAM), RAM_SPEC_GROUP(PartType.RAM), STORAGE_MODEL(PartType.STORAGE);

    private final PartType type;
    CatalogModelKind(PartType type) { this.type = type; }
    public PartType type() { return type; }
    public boolean isSpecGroup() { return this == RAM_SPEC_GROUP; }
}
