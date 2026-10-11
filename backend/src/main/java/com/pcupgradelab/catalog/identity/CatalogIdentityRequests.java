package com.pcupgradelab.catalog.identity;

import com.pcupgradelab.catalog.CatalogSourceInput;
import com.pcupgradelab.pc.PartType;
import java.util.HashSet;
import java.util.List;

/** 내부 검토·승인 묶음용 입력. 이 입력을 받는 쓰기 HTTP API는 제공하지 않는다. */
public final class CatalogIdentityRequests {
    private CatalogIdentityRequests() { }

    public record ModelSource(CatalogSourceInput source, String reviewScope) {
        public ModelSource {
            if (source == null) throw new IllegalArgumentException("model source is required");
            reviewScope = IdentityValues.text(reviewScope, "reviewScope", 1000);
        }
    }

    public record ModelAlias(String rawAlias, int sourceIndex) {
        public ModelAlias {
            if (rawAlias == null || rawAlias.isBlank() || rawAlias.length() > 500)
                throw new IllegalArgumentException("rawAlias must be nonblank (max 500 characters)");
            if (IdentityValues.alias(rawAlias).isBlank()) throw new IllegalArgumentException("normalized alias must be nonblank");
            if (sourceIndex < 0) throw new IllegalArgumentException("alias sourceIndex must be nonnegative");
        }
    }

    public record ModelRegistration(String canonicalId, PartType type, String manufacturer, String modelName,
                                    CatalogModelKind kind, CatalogRole role, String family, String series,
                                    List<ModelSource> sources, List<ModelAlias> aliases) {
        public ModelRegistration {
            canonicalId = IdentityValues.uuid(canonicalId, "model canonicalId");
            if (kind == null || kind.type() != type) throw new IllegalArgumentException("model kind and type must agree");
            if (role == null) throw new IllegalArgumentException("model role is required");
            manufacturer = IdentityValues.text(manufacturer, "manufacturer", 100);
            modelName = IdentityValues.text(modelName, "modelName", 255);
            family = IdentityValues.optional(family, "family", 100);
            series = IdentityValues.optional(series, "series", 128);
            if (sources == null || sources.isEmpty() || sources.size() > 64 || sources.stream().anyMatch(java.util.Objects::isNull))
                throw new IllegalArgumentException("model requires 1..64 non-null sources");
            if (aliases == null || aliases.size() > 64 || aliases.stream().anyMatch(java.util.Objects::isNull))
                throw new IllegalArgumentException("aliases must be a non-null list (max 64)");
            sources = List.copyOf(sources);
            aliases = List.copyOf(aliases);
            var sourceKeys = new HashSet<String>();
            for (var source : sources) {
                String key = source.source().sourceName() + "/" + (source.source().externalId() == null
                        ? source.source().sourceUrl() : source.source().externalId());
                if (!sourceKeys.add(key)) throw new IllegalArgumentException("duplicate model source");
            }
            var aliasKeys = new HashSet<String>();
            for (var alias : aliases) {
                if (alias.sourceIndex() >= sources.size()) throw new IllegalArgumentException("alias sourceIndex is out of range");
                if (!aliasKeys.add(alias.sourceIndex() + "/" + IdentityValues.alias(alias.rawAlias())))
                    throw new IllegalArgumentException("duplicate normalized alias at the same source");
            }
        }
    }

    public record ProductBinding(String productId, String canonicalId, String modelId,
                                 CatalogIdentityKind identityKind, CatalogRole role,
                                 long evidenceSourceId, String reviewScope) {
        public ProductBinding {
            productId = IdentityValues.text(productId, "productId", 128);
            canonicalId = IdentityValues.uuid(canonicalId, "product canonicalId");
            modelId = IdentityValues.optional(modelId, "modelId", 128);
            if (identityKind == null || role == null || evidenceSourceId <= 0)
                throw new IllegalArgumentException("identity kind, role and product evidence are required");
            if (identityKind == CatalogIdentityKind.MODEL_REFERENCE && modelId == null)
                throw new IllegalArgumentException("MODEL_REFERENCE requires a model");
            if (identityKind == CatalogIdentityKind.LEGACY_UNCLASSIFIED && (modelId != null || role != CatalogRole.UNASSIGNED))
                throw new IllegalArgumentException("unclassified products must not infer a model or role");
            reviewScope = IdentityValues.text(reviewScope, "reviewScope", 1000);
        }
    }
}
