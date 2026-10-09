package com.pcupgradelab.catalog.identity;

import com.pcupgradelab.catalog.CatalogSourceName;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** 검토된 원본 식별자에서 공유용 제안 ID만 계산한다. 로컬 UUID를 바꾸거나 DB를 조회하지 않는다. */
public final class CanonicalCatalogIds {
    private CanonicalCatalogIds() { }

    public static String productForReviewedSource(CatalogSourceName sourceName, String externalId) {
        if (sourceName == null) throw new IllegalArgumentException("sourceName is required");
        String identity = IdentityValues.text(externalId, "externalId", 128);
        if (sourceName == CatalogSourceName.BUILDCORES) identity = IdentityValues.uuid(identity, "externalId");
        String key = "pc-upgrade-lab/catalog/product/v1\0" + sourceName.name() + "\0" + identity;
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
    }

    public static String requireCanonicalId(String value) { return IdentityValues.uuid(value, "canonicalId"); }
}
