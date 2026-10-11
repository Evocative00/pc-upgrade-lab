package com.pcupgradelab.catalog.identity;

import com.pcupgradelab.catalog.CatalogProduct;
import com.pcupgradelab.catalog.CatalogSourceName;
import com.pcupgradelab.catalog.CatalogVerificationStatus;
import com.pcupgradelab.pc.PartType;
import java.time.Instant;
import java.util.List;

public final class CatalogIdentityDtos {
    private CatalogIdentityDtos() { }

    public record Model(String id, String canonicalId, PartType type, String manufacturer, String modelName,
                        CatalogModelKind kind, CatalogRole role, CatalogVerificationStatus verificationStatus,
                        String family, String series, Instant createdAt, Instant updatedAt) {
        static Model from(CatalogModel model) {
            return new Model(model.getId(), model.getCanonicalId(), model.getType(), model.getManufacturer(), model.getModelName(),
                    model.getKind(), model.getRole(), model.getVerificationStatus(), model.getFamily(), model.getSeries(),
                    model.getCreatedAt(), model.getUpdatedAt());
        }
    }

    public record Source(CatalogSourceName sourceName, String externalId, String sourceRevision,
                         String sourceUrl, Instant retrievedAt, String reviewScope) {
        static Source from(CatalogModelSource source) {
            return new Source(source.getSourceName(), source.getExternalId(), source.getSourceRevision(),
                    source.getSourceUrl(), source.getRetrievedAt(), source.getReviewScope());
        }
    }

    public record Alias(String rawAlias, String normalizedAlias, Source evidence) {
        static Alias from(CatalogModelAlias alias) {
            return new Alias(alias.getRawAlias(), alias.getNormalizedAlias(), Source.from(alias.getSource()));
        }
    }

    /** 관련 제품은 후보다. 반환했다고 PC 연결·판매 SKU·호환 조건을 승인한 것은 아니다. */
    public record ProductCandidate(String id, String canonicalId, PartType type, String manufacturer,
                                   String modelName, String partNumber, CatalogIdentityKind identityKind, CatalogRole role) {
        static ProductCandidate from(CatalogProduct product) {
            return new ProductCandidate(product.getId(), product.getCanonicalId(), product.getType(), product.getManufacturer(),
                    product.getModelName(), product.getPartNumber(), product.getIdentityKind(), product.getRole());
        }
    }

    public record PageResponse(List<Model> items, int page, int size, long totalElements, int totalPages) {
        public PageResponse { items = List.copyOf(items); }
    }

    /** rawPayload와 내부 출처 행 ID는 공개 응답에 포함하지 않는다. */
    public record Detail(Model model, List<Alias> aliases, List<Source> sources, List<ProductCandidate> productCandidates) {
        public Detail { aliases = List.copyOf(aliases); sources = List.copyOf(sources); productCandidates = List.copyOf(productCandidates); }
    }
}
