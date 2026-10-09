package com.pcupgradelab.catalog.expansion;

import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Research DTOs only: none of these records are catalog creation requests or DB entities. */
public record CatalogExpansionPreview(
        int schemaVersion, String previewDate, String stage, Authorization authorization,
        Summary summary, CanonicalIdPlan canonicalIdPlan, List<SourceHash> sourceHashes,
        List<ExistingProductMapping> existingProductMappings, List<Candidate> candidates,
        List<HeldModel> heldAdditionalModels) {

    public record Authorization(boolean implementationPreviewApproved, boolean catalogAdoptionApproved,
            boolean databaseApplyAuthorized, boolean priceCollectionAuthorized,
            boolean requiresUserConfirmationBeforeApply) { }

    public record Summary(int existingProductCount, int retainedApprovedPriceCount, int researchCandidateCount,
            int latestFirstCandidateCount, int followupCandidateCount, int heldAdditionalModelCount,
            int partNumberUnknownCount, int newProductsCreated, int newPriceObservationsCreated) { }

    public record CanonicalIdPlan(String algorithm, String namespace, List<String> identitySeedFields,
            boolean nameBasedMatchingAllowed, boolean localProductIdsPreserved, String status) { }

    public record SourceHash(String file, String normalizedSha256) { }

    public record ExpectedProduct(String category, String manufacturer, String modelName, String partNumber) { }

    public record ExistingProductMapping(String sourceName, String sourceExternalId, UUID canonicalProductId,
            UUID localProductId, ExpectedProduct expectedProduct, String lookupStrategy,
            String typeConflictBehavior, String mappingReviewStatus, boolean mappingApproved,
            String sourceManifestFile, String sourceRecordSha256, String approvedPriceStatus) { }

    public record ReviewGate(String code, String status, String detail) { }

    public record UnitValidation(String status, String capacityBasis, String note) { }

    public record Candidate(String candidateKey, String category, String manufacturer, String modelName,
            String researchPartNumber, String role, String researchGroup, String researchSourceFile,
            String selectionReason, JsonNode selectionEvidence, String researchReadiness,
            String lifeCycleStatus, String lifeCycleDateRaw,
            int reviewOrder, String reviewPhase, String eligibility, String adoptionDecision,
            UUID proposedCanonicalProductId, UUID localProductId, boolean userConfirmationRequired,
            boolean databaseApplyAuthorized, String saleIdentityStatus, String priceStatus,
            String compatibilityReviewStatus, String identificationLevel, JsonNode knownSpecificationFacts,
            List<String> unconfirmedFactPaths, UnitValidation unitValidation, List<ReviewGate> reviewGates,
            List<JsonNode> officialSources, List<JsonNode> domesticSources,
            List<String> compatibilityNotes, List<String> identificationNotes, List<String> openQuestions) { }

    public record HeldModel(String modelName, String category, String manufacturer, String researchGroup,
            String researchSourceFile, String status, String reasonForHold, UUID proposedCanonicalProductId,
            UUID localProductId, String eligibility, boolean userConfirmationRequired,
            boolean databaseApplyAuthorized, String priceStatus, JsonNode knownSpecificationFacts,
            List<JsonNode> officialSources, List<String> openQuestions) { }
}
