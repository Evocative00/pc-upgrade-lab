package com.pcupgradelab.catalog.pilot;

import com.pcupgradelab.catalog.CatalogEntryCreateRequest;
import com.pcupgradelab.catalog.CatalogProductCreateRequest;
import com.pcupgradelab.catalog.CatalogSourceInput;
import com.pcupgradelab.catalog.CatalogSourceName;
import com.pcupgradelab.catalog.CatalogSpecification;
import com.pcupgradelab.catalog.identity.CanonicalCatalogIds;
import com.pcupgradelab.catalog.identity.CatalogIdentityKind;
import com.pcupgradelab.catalog.identity.CatalogIdentityRequests;
import com.pcupgradelab.catalog.identity.CatalogModelKind;
import com.pcupgradelab.catalog.identity.CatalogRole;
import com.pcupgradelab.catalog.memory.CpuMemorySupport;
import com.pcupgradelab.catalog.price.CatalogPriceImportBatch;
import com.pcupgradelab.catalog.storage.MotherboardStorageSupport;
import com.pcupgradelab.pc.PartType;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;

/** Validates reviewed public inputs against real domain constructors without any persistence path. */
public final class CatalogPilotPreviewLoader {
    public static final String PREVIEW_FILE = "data/catalog-review/pilot-import-preview-2026-10-10.json";
    private static final String PREFIX = "data/catalog-review/";
    private static final String DATE = "2026-10-10";
    private static final int MAX_BYTES = 5 * 1024 * 1024;
    private static final Set<String> SOURCE_FILES = Set.of(
            "pilot-selection-2026-10-10.json", "catalog-selection-2026-10-09.json",
            "pilot-platform-evidence-2026-10-10.json", "pilot-ram-gpu-evidence-2026-10-10.json",
            "pilot-storage-evidence-2026-10-10.json", "pilot-existing-prices-2026-10-10.json",
            "pilot-existing-prices-2026-10-10.json.report.json", "pilot-existing-price-assessment-2026-10-10.json",
            "pilot-model-price-review-2026-10-10.json", "pilot-storage-price-review-2026-10-10.json");
    private static final Map<Integer, String> NEW_SOURCE_IDENTITIES = Map.of(
            3, "intel:ark:241067:model", 4, "intel:ark:245694:model", 6, "asrock:B850M Pro RS:model",
            7, "msi:MAG B860M MORTAR WIFI:model", 8, "asus:TUF GAMING B860-PLUS WIFI:model",
            13, "samsung:MZ-77E1T0BW", 14, "samsung:MZ-V9P1T0BW");
    private final JsonMapper mapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    private final JsonMapper previewMapper = mapper.rebuild()
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES).build();

    public Result load(Path repositoryRoot) {
        return read(readPublicFile(realRoot(repositoryRoot), PREVIEW_FILE), repositoryRoot);
    }

    public Result read(byte[] bytes, Path repositoryRoot) {
        try {
            require(bytes != null && bytes.length > 0 && bytes.length <= MAX_BYTES, "Preview must contain 1 byte..5 MiB");
            var preview = previewMapper.treeToValue(object(mapper.readTree(bytes)), Preview.class);
            require(preview.schemaVersion() == 1 && DATE.equals(preview.previewDate())
                    && "DB_FREE_DETAILED_REVIEW_PENDING_APPLY_APPROVAL".equals(preview.stage()), "Unsupported pilot version/date/stage");
            var approval = preview.approvalScope();
            require(approval.selectionAndDetailedReviewApproved() && !approval.databaseApplyApproved()
                    && !approval.priceApplyApproved(), "Preview cannot authorize database or price application");
            var policy = preview.executionPolicy();
            require(!policy.applyImplemented() && policy.resolveExistingIdsByExactSource()
                    && policy.preserveExistingSpecsAndPrices() && policy.doNotPromoteVerificationOrActivation()
                    && policy.pcRowsChanged() == 0 && policy.migrationsRequired() == 0
                    && policy.futureApplyMustRecheckDbStateAndBeIdempotent(), "Preview cannot promote status, activation or writes");
            var documents = verifySources(preview, realRoot(repositoryRoot));
            return validateProducts(preview, documents);
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("Invalid pilot preview or typed review facts", ex);
        }
    }

    private Map<String, JsonNode> verifySources(Preview preview, Path root) {
        require(preview.sourceHashes() != null && preview.sourceHashes().size() == SOURCE_FILES.size(), "Exactly 10 reviewed source hashes are required");
        var documents = new HashMap<String, JsonNode>();
        for (var source : preview.sourceHashes()) {
            require(source != null && source.file() != null && source.file().startsWith(PREFIX)
                    && SOURCE_FILES.contains(source.file().substring(PREFIX.length()))
                    && source.normalizedSha256() != null && source.normalizedSha256().matches("[0-9a-f]{64}"), "Only exact public pilot JSON sources are allowed");
            String normalized = normalizedUtf8(readPublicFile(root, source.file()));
            require(sha256(normalized).equals(source.normalizedSha256()), "Reviewed source hash mismatch: " + source.file());
            require(documents.putIfAbsent(source.file(), object(mapper.readTree(normalized))) == null, "Duplicate reviewed source path");
        }
        JsonNode selection = source(documents, "pilot-selection-2026-10-10.json");
        require(selection.path("selectionApproval").path("databaseOrPriceApplyApproved").isBoolean()
                && !selection.path("selectionApproval").path("databaseOrPriceApplyApproved").booleanValue(), "Selection cannot grant apply authorization");
        require(source(documents, "pilot-existing-prices-2026-10-10.json.report.json").path("succeeded").intValue() == 6
                && source(documents, "pilot-existing-prices-2026-10-10.json.report.json").path("rejectedCount").intValue() == 0,
                "Six successful existing price observations are required");
        require(sameFacts(preview.cpuSupportPairs(), source(documents, "pilot-platform-evidence-2026-10-10.json").get("cpuSupportPairs"))
                && sameFacts(preview.existingPriceAssessment(), source(documents, "pilot-existing-price-assessment-2026-10-10.json"))
                && sameFacts(preview.exactStorageSellerQuoteReview(), source(documents, "pilot-storage-price-review-2026-10-10.json")), "Embedded reviewed evidence changed");
        var expectedQvl = mapper.createArrayNode();
        for (JsonNode item : source(documents, "pilot-ram-gpu-evidence-2026-10-10.json").path("items")) {
            if ("RAM".equals(item.path("product").path("type").stringValue())) {
                expectedQvl.add(mapper.createObjectNode().set("selectionNumber", item.get("selectionNumber")).set("evidence", item.get("qvlEvidence")));
            }
        }
        require(sameFacts(preview.ramQvlReviews(), expectedQvl), "RAM QVL scope changed");
        return documents;
    }

    private Result validateProducts(Preview preview, Map<String, JsonNode> documents) {
        require(preview.products() != null && preview.products().size() == 14 && preview.models() != null && preview.models().size() == 13,
                "Exactly 14 products and 13 models are required");
        JsonNode selection = source(documents, "pilot-selection-2026-10-10.json");
        require(selection.path("items").size() == 14, "Exactly 14 selected references are required");
        var models = new HashMap<String, CatalogIdentityRequests.ModelRegistration>();
        for (var model : preview.models()) {
            var input = model.registration();
            require(input != null && models.putIfAbsent(model.modelKey(), input) == null
                    && model.modelKey().equals(input.kind() + ":" + input.manufacturer() + ":" + input.modelName())
                    && input.canonicalId().equals(UUID.nameUUIDFromBytes(("pc-upgrade-lab/catalog/model/v1\0" + model.modelKey()).getBytes(StandardCharsets.UTF_8)).toString())
                    && input.role() == CatalogRole.INSTALLED_PC_REFERENCE && input.family() == null && input.series() == null && input.aliases().isEmpty(), "Invalid model identity or unreviewed model inference");
        }
        var canonicalIds = new HashSet<String>();
        var expectedModelSources = new HashMap<String, List<CatalogIdentityRequests.ModelSource>>();
        var typedPrices = new ArrayList<CatalogPriceImportBatch.Item>();
        int reused = 0, created = 0, memory = 0, profiles = 0, slots = 0;
        for (int index = 0; index < preview.products().size(); index++) {
            var part = preview.products().get(index);
            require(part.selectionNumber() == index + 1 && part.evidenceSelectionNumber() == part.selectionNumber()
                    && part.localProductId() == null, "Selected order and unqueried local IDs must be preserved");
            JsonNode selected = find(selection.path("items"), "selectionNumber", part.selectionNumber());
            require(selected != null, "Selected product reference is missing");
            boolean existing = !selected.path("existingCatalogExternalId").isNull();
            String expectedEvidenceFile = PREFIX + (part.selectionNumber() <= 8 ? "pilot-platform-evidence-2026-10-10.json"
                    : part.selectionNumber() <= 12 ? "pilot-ram-gpu-evidence-2026-10-10.json" : "pilot-storage-evidence-2026-10-10.json");
            require(expectedEvidenceFile.equals(part.evidenceFile()), "Product evidence source changed");
            JsonNode evidenceDoc = documents.get(part.evidenceFile());
            JsonNode detail = find(evidenceDoc.path(part.selectionNumber() >= 13 ? "candidates" : "items"), "selectionNumber", part.selectionNumber());
            require(detail != null && selected.path("type").stringValue().equals(part.product().type().name()), "Selected product type changed");
            JsonNode old = existing ? find(source(documents, "catalog-selection-2026-10-09.json").path("items"), "externalId", selected.path("existingCatalogExternalId").stringValue()) : null;
            JsonNode expectedSpec = existing ? old.path("specification") : detail.get(part.selectionNumber() >= 13 ? "storageSpecification" : "spec");
            require(sameFacts(part.specification(), expectedSpec), "Reviewed specification changed for selection " + part.selectionNumber());
            CatalogSpecification specification = specification(part.product().type(), part.specification());
            if (existing) {
                require("REUSE_EXISTING".equals(part.operation()) && sameFacts(mapper.valueToTree(part.product()), old.get("product")), "Existing product identity must be preserved");
                require(part.sourceIdentity().sourceName() == CatalogSourceName.BUILDCORES
                        && selected.path("existingCatalogExternalId").stringValue().equals(part.sourceIdentity().externalId()), "Existing exact source identity changed");
                reused++;
            } else {
                require("CREATE_PROPOSAL".equals(part.operation()) && part.product().manufacturer().equals(selected.path("manufacturer").stringValue())
                        && part.product().modelName().equals(selected.path("modelName").stringValue())
                        && java.util.Objects.equals(part.product().partNumber(), part.selectionNumber() >= 13 ? detail.path("selectedRetailPartNumber").stringValue() : null)
                        && part.sourceIdentity().sourceName() == CatalogSourceName.MANUFACTURER
                        && NEW_SOURCE_IDENTITIES.get(part.selectionNumber()).equals(part.sourceIdentity().externalId()), "New proposal must preserve selected manufacturer identity");
                new CatalogEntryCreateRequest(part.product(), specification, List.of(part.manufacturerEvidence()));
                created++;
            }
            require(canonicalIds.add(part.proposedCanonicalId()) && part.proposedCanonicalId().equals(CanonicalCatalogIds.productForReviewedSource(
                    part.sourceIdentity().sourceName(), part.sourceIdentity().externalId())), "Product canonical ID does not match reviewed exact source identity");
            validateModelLink(part, models);
            validateManufacturerEvidence(part, selected, documents);
            expectedModelSources.computeIfAbsent(part.modelKey(), key -> new ArrayList<>()).add(new CatalogIdentityRequests.ModelSource(part.manufacturerEvidence(), part.bindingReviewScope()));
            require(sameFacts(part.memorySupport(), detail.get("memorySupport")) && sameFacts(part.storageSupport(), detail.get("storageSupport")), "CPU memory or motherboard storage facts changed");
            if (part.memorySupport() != null && !part.memorySupport().isNull()) {
                require(part.product().type() == PartType.CPU, "Only CPUs may carry CPU memory support");
                mapper.treeToValue(part.memorySupport(), CpuMemorySupport.class); memory++;
            }
            if (part.storageSupport() != null && !part.storageSupport().isNull()) {
                require(part.product().type() == PartType.MOTHERBOARD, "Only motherboards may carry slot support");
                var support = mapper.treeToValue(part.storageSupport(), MotherboardStorageSupport.class);
                require(support.revisionScope() == MotherboardStorageSupport.RevisionScope.MODEL && support.hardwareRevision() == null,
                        "Unknown hardware revisions must remain model-scoped");
                profiles++; slots += support.slots().size();
            }
            validatePrice(part, selected, documents, typedPrices);
        }
        require(reused == 7 && created == 7 && memory == 4 && profiles == 4 && typedPrices.size() == 6, "Typed proposal counts changed");
        for (var entry : models.entrySet()) require(entry.getValue().sources().equals(expectedModelSources.get(entry.getKey())), "Model source scope or product relation changed");
        require(models.values().stream().filter(model -> model.kind() == CatalogModelKind.RAM_SPEC_GROUP).count() == 1
                && preview.products().get(8).modelKey().equals(preview.products().get(9).modelKey()), "RAM kits must share one bounded specification group");
        var batch = mapper.treeToValue(source(documents, "pilot-existing-prices-2026-10-10.json"), CatalogPriceImportBatch.class);
        require(new HashSet<>(typedPrices).equals(new HashSet<>(batch.items())) && typedPrices.size() == batch.items().size(), "Existing price payload changed");
        int heldQuotes = countQuoted(source(documents, "pilot-model-price-review-2026-10-10.json").path("items"));
        int storageQuotes = source(documents, "pilot-storage-price-review-2026-10-10.json").path("observations").size();
        validateSummary(preview.summary(), slots, heldQuotes, storageQuotes);
        require(preview.requiredBeforeApply() != null && !preview.requiredBeforeApply().isEmpty(), "Apply blockers must remain explicit");
        return new Result(14, reused, created, 13, 14, 14, documents.size(), profiles, slots, 6, heldQuotes, storageQuotes);
    }

    private void validateModelLink(Product part, Map<String, CatalogIdentityRequests.ModelRegistration> models) {
        var model = models.get(part.modelKey());
        require(model != null && model.type() == part.product().type(), "Product model type differs");
        CatalogModelKind kind = switch (part.product().type()) {
            case CPU -> CatalogModelKind.CPU_MODEL; case MOTHERBOARD -> CatalogModelKind.BOARD_MODEL;
            case RAM -> CatalogModelKind.RAM_SPEC_GROUP; case GPU -> CatalogModelKind.GPU_CHIP_MODEL;
            case STORAGE -> CatalogModelKind.STORAGE_MODEL; default -> throw new IllegalArgumentException("Unsupported pilot type");
        };
        String modelName = switch (part.selectionNumber()) {
            case 9, 10 -> "DDR5 DIMM 16GB non-ECC unbuffered specification group";
            case 11 -> "GeForce RTX 5060"; case 12 -> "Radeon RX 9060 XT"; default -> part.product().modelName();
        };
        String maker = part.selectionNumber() == 11 ? "NVIDIA" : part.selectionNumber() == 12 ? "AMD" : part.product().manufacturer();
        boolean modelReference = part.product().type() == PartType.MOTHERBOARD || part.selectionNumber() == 3 || part.selectionNumber() == 4;
        CatalogIdentityKind identityKind = part.product().type() == PartType.RAM ? CatalogIdentityKind.RETAIL_KIT
                : modelReference ? CatalogIdentityKind.MODEL_REFERENCE : CatalogIdentityKind.PHYSICAL_VARIANT;
        CatalogRole role = modelReference ? CatalogRole.INSTALLED_PC_REFERENCE
                : part.product().type() == PartType.RAM ? CatalogRole.PURCHASE_CANDIDATE : CatalogRole.BOTH;
        String scope = part.product().type() == PartType.RAM
                ? "Only shared DDR5 DIMM 16GB non-ECC unbuffered facts. Kit PN is not module PN; rank, IC and exact module identity remain unknown."
                : "Reviewed manufacturer model identity only; no exact physical variant, full PC compatibility or current-price certification.";
        require(model.kind() == kind && model.modelName().equals(modelName) && model.manufacturer().equals(maker)
                && part.identityKind() == identityKind && part.role() == role && scope.equals(part.bindingReviewScope()),
                "Model/physical variant or installed/purchase role was promoted");
    }

    private void validateManufacturerEvidence(Product part, JsonNode selected, Map<String, JsonNode> documents) {
        var source = part.manufacturerEvidence();
        require(source.sourceName() == CatalogSourceName.MANUFACTURER && "pilot-review-2026-10-10".equals(source.sourceRevision())
                && java.util.Objects.equals(source.externalId(), part.selectionNumber() <= 12 && part.sourceIdentity().sourceName() == CatalogSourceName.BUILDCORES
                        ? "pilot:" + part.sourceIdentity().externalId() : NEW_SOURCE_IDENTITIES.get(part.selectionNumber())), "Manufacturer reviewed source identity changed");
        String url = selected.path("officialIdentityReview").path("url").stringValue();
        if (part.selectionNumber() >= 13) {
            JsonNode detail = find(source(documents, "pilot-storage-evidence-2026-10-10.json").path("candidates"), "selectionNumber", part.selectionNumber());
            JsonNode official = find(source(documents, "pilot-storage-evidence-2026-10-10.json").path("sources"), "sourceKey",
                    detail.path("domesticSaleIdentityReview").path("manufacturerIdentitySourceKey").stringValue());
            url = official.path("url").stringValue();
        }
        require(source.sourceUrl().equals(url), "Manufacturer evidence URL changed");
        JsonNode raw = mapper.valueToTree(source.rawPayload());
        require(raw.size() == 6 && "REVIEWED_SPEC_EXTRACT".equals(raw.path("recordKind").stringValue())
                && part.evidenceFile().equals(raw.path("evidenceFile").stringValue()) && raw.path("selectionNumber").isIntegralNumber()
                && raw.path("selectionNumber").intValue() == part.selectionNumber() && DATE.equals(raw.path("pageCheckDate").stringValue())
                && "DATE_ONLY".equals(raw.path("pageCheckTimePrecision").stringValue())
                && "Curated review-extract preparation time, not a precise original page fetch time".equals(raw.path("retrievedAtMeaning").stringValue()), "Reviewed extraction must preserve source, date precision and scope");
    }

    private void validatePrice(Product part, JsonNode selected, Map<String, JsonNode> documents, List<CatalogPriceImportBatch.Item> prices) {
        JsonNode review = object(part.priceReview());
        Set<String> allowed = Set.of("status", "priceImportItem", "historicalAmountKrw", "deltaKrw", "modelQuote");
        for (String field : review.propertyNames()) require(allowed.contains(field), "Unknown price review property");
        JsonNode exact = find(source(documents, "pilot-existing-prices-2026-10-10.json").path("items"), "product.externalId", part.sourceIdentity().externalId());
        if (exact != null) {
            require("REOBSERVED_PENDING_USER_APPROVAL".equals(review.path("status").stringValue())
                    && sameFacts(exact, review.get("priceImportItem")) && review.get("modelQuote") == null
                    && review.path("historicalAmountKrw").isIntegralNumber() && review.path("deltaKrw").isIntegralNumber()
                    && review.path("historicalAmountKrw").longValue() == selected.path("historicalApprovedPrice").path("amountKrw").longValue(), "Existing observation, historical unit or price hold changed");
            var item = mapper.treeToValue(exact, CatalogPriceImportBatch.Item.class);
            require(item.product().externalId().equals(part.sourceIdentity().externalId())
                    && item.product().manufacturer().equals(part.product().manufacturer()) && item.product().modelName().equals(part.product().modelName())
                    && java.util.Objects.equals(item.product().partNumber(), part.product().partNumber())
                    && item.price().amountKrw() - review.path("historicalAmountKrw").longValue() == review.path("deltaKrw").longValue(), "Price identity or delta changed");
            if (part.product().type() == PartType.RAM) require(item.offer().saleUnit() == CatalogPriceImportBatch.SaleUnit.RAM_KIT
                    && Integer.valueOf(2).equals(item.offer().moduleCount()), "RAM price must remain one two-module retail kit");
            else require(item.offer().saleUnit() == CatalogPriceImportBatch.SaleUnit.PRODUCT, "Non-RAM price must remain one product");
            prices.add(item);
        } else {
            String status = part.selectionNumber() == 10 ? "HELD_EXACT_DOMESTIC_KIT_MAPPING_MISSING"
                    : part.selectionNumber() >= 13 ? "EXACT_SELLER_QUOTE_REVIEWED_PENDING_CONTRACT_AND_USER_APPROVAL"
                    : "HELD_EXACT_PHYSICAL_SALE_VARIANT_UNCONFIRMED";
            require(status.equals(review.path("status").stringValue()) && review.path("priceImportItem").isNull()
                    && review.get("historicalAmountKrw") == null && review.get("deltaKrw") == null,
                    "A held model or new storage quote cannot become an import observation");
            JsonNode modelQuote = find(source(documents, "pilot-model-price-review-2026-10-10.json").path("items"), "selectionNumber", part.selectionNumber());
            require(sameFacts(review.get("modelQuote"), modelQuote), "Quarantined model quote changed");
        }
    }

    private void validateSummary(JsonNode value, int slots, int heldQuotes, int storageQuotes) {
        Map<String, Integer> expected = Map.ofEntries(Map.entry("reviewedParts", 14), Map.entry("reusedProducts", 7),
                Map.entry("proposedNewProducts", 7), Map.entry("proposedModelRecords", 13), Map.entry("proposedModelSources", 14),
                Map.entry("proposedProductBindings", 14), Map.entry("proposedStorageProfiles", 4), Map.entry("proposedStorageSlots", slots),
                Map.entry("reobservedExistingPriceCandidates", 6), Map.entry("quarantinedModelPriceQuotes", heldQuotes),
                Map.entry("exactNewStorageSellerQuotes", storageQuotes), Map.entry("databaseReads", 0), Map.entry("databaseWrites", 0),
                Map.entry("priceCoverageIncreaseFromReobservations", 0));
        require(value != null && value.isObject() && value.size() == expected.size() && storageQuotes == 2 && heldQuotes == 5,
                "Summary fields or quote counts changed");
        expected.forEach((key, count) -> require(value.path(key).isIntegralNumber() && value.path(key).intValue() == count, "Invalid summary: " + key));
    }

    private CatalogSpecification specification(PartType type, JsonNode value) {
        return switch (type) {
            case CPU -> mapper.treeToValue(value, CatalogSpecification.Cpu.class);
            case MOTHERBOARD -> mapper.treeToValue(value, CatalogSpecification.Motherboard.class);
            case RAM -> mapper.treeToValue(value, CatalogSpecification.Ram.class);
            case GPU -> mapper.treeToValue(value, CatalogSpecification.Gpu.class);
            case STORAGE -> mapper.treeToValue(value, CatalogSpecification.Storage.class);
            default -> throw new IllegalArgumentException("Unsupported pilot specification type");
        };
    }

    private static Path realRoot(Path root) {
        try { require(root != null, "Repository root is required"); return root.toRealPath(); }
        catch (IOException ex) { throw new IllegalArgumentException("Repository root is unavailable", ex); }
    }
    private static byte[] readPublicFile(Path root, String relative) {
        try {
            Path file = root.resolve(relative).toRealPath();
            require(file.startsWith(root) && Files.isRegularFile(file), "Reviewed source is outside the repository");
            try (var input = Files.newInputStream(file)) {
                byte[] bytes = input.readNBytes(MAX_BYTES + 1);
                require(bytes.length > 0 && bytes.length <= MAX_BYTES, "Reviewed source must contain 1 byte..5 MiB");
                return bytes;
            }
        } catch (IOException ex) { throw new IllegalArgumentException("Reviewed public source is unavailable: " + relative, ex); }
    }
    private static String normalizedUtf8(byte[] bytes) {
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            return (text.startsWith("\uFEFF") ? text.substring(1) : text).replace("\r\n", "\n");
        } catch (java.nio.charset.CharacterCodingException ex) { throw new IllegalArgumentException("Reviewed source must be valid UTF-8", ex); }
    }
    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    private static JsonNode source(Map<String, JsonNode> sources, String name) { return sources.get(PREFIX + name); }
    private static JsonNode object(JsonNode node) { require(node != null && node.isObject(), "A JSON object is required"); return node; }
    private static JsonNode find(JsonNode items, String field, Object value) {
        JsonNode match = null;
        for (JsonNode item : items) {
            JsonNode node = item;
            for (String segment : field.split("\\.")) node = node.path(segment);
            if ((value instanceof Integer number && node.isIntegralNumber() && node.intValue() == number)
                    || (value instanceof String text && node.isString() && text.equals(node.stringValue()))) {
                require(match == null, "Duplicate reviewed source identity"); match = item;
            }
        }
        return match;
    }
    private static int countQuoted(JsonNode items) { int count = 0; for (JsonNode item : items) if (!item.path("price").isNull() && !item.path("price").isMissingNode()) count++; return count; }
    private static boolean sameFacts(JsonNode left, JsonNode right) {
        if (left == null || left.isNull()) return right == null || right.isNull();
        if (right == null || right.isNull()) return false;
        if (left.isNumber() && right.isNumber()) return left.decimalValue().compareTo(right.decimalValue()) == 0;
        if (left.isObject() && right.isObject()) {
            if (left.size() != right.size()) return false;
            for (String name : left.propertyNames()) if (!right.has(name) || !sameFacts(left.get(name), right.get(name))) return false;
            return true;
        }
        if (left.isArray() && right.isArray()) {
            if (left.size() != right.size()) return false;
            for (int index = 0; index < left.size(); index++) if (!sameFacts(left.get(index), right.get(index))) return false;
            return true;
        }
        return left.equals(right);
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }

    public record Result(int parts, int reusedProducts, int newProducts, int models, int modelSources, int bindings,
                         int sourceHashes, int storageProfiles, int storageSlots, int priceCandidates, int heldModelQuotes, int storageSellerQuotes) { }
    public record Preview(int schemaVersion, String previewDate, String stage, Approval approvalScope,
                          List<SourceHash> sourceHashes, JsonNode summary, Policy executionPolicy,
                          List<Model> models, List<Product> products, JsonNode cpuSupportPairs, JsonNode ramQvlReviews,
                          JsonNode existingPriceAssessment, JsonNode exactStorageSellerQuoteReview, List<String> requiredBeforeApply) { }
    public record Approval(boolean selectionAndDetailedReviewApproved, boolean databaseApplyApproved, boolean priceApplyApproved) { }
    public record Policy(boolean applyImplemented, boolean resolveExistingIdsByExactSource, boolean preserveExistingSpecsAndPrices,
                         boolean doNotPromoteVerificationOrActivation, int pcRowsChanged, int migrationsRequired, boolean futureApplyMustRecheckDbStateAndBeIdempotent) { }
    public record SourceHash(String file, String normalizedSha256) { }
    public record Model(String modelKey, CatalogIdentityRequests.ModelRegistration registration) { }
    public record SourceIdentity(CatalogSourceName sourceName, String externalId) { }
    public record Product(int selectionNumber, String operation, SourceIdentity sourceIdentity, String proposedCanonicalId,
                          String localProductId, CatalogProductCreateRequest product, JsonNode specification,
                          CatalogSourceInput manufacturerEvidence, String modelKey, CatalogIdentityKind identityKind, CatalogRole role,
                          String bindingReviewScope, JsonNode memorySupport, JsonNode storageSupport, JsonNode priceReview,
                          String evidenceFile, int evidenceSelectionNumber, List<String> openChecks) { }
}
