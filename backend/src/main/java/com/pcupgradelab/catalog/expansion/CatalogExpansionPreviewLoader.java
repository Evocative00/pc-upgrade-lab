package com.pcupgradelab.catalog.expansion;

import com.pcupgradelab.catalog.CatalogSourceName;
import com.pcupgradelab.catalog.identity.CanonicalCatalogIds;
import java.io.IOException;
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
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

/** Validates a bundled review against public repository sources without opening any database. */
public final class CatalogExpansionPreviewLoader {
    static final String RESOURCE = "catalog/expansion/catalog-expansion-preview-2026-10-09.json";
    static final String RESEARCH_FILE = "data/catalog-review/catalog-expansion-candidates-2026-10-09.json";
    static final String EXISTING_FILE = "data/catalog-review/catalog-selection-2026-10-09.json";
    private static final String DATE = "2026-10-09";
    private static final int MAX_BYTES = 5 * 1024 * 1024;
    private static final List<String> GROUP_ORDER = List.of("SSD", "CURRENT_INTEL", "CURRENT_AMD",
            "CURRENT_GPU", "LEGACY_INTEL", "DESKTOP_RAM");
    private static final Map<String, Integer> GROUP_COUNTS = Map.of("SSD", 9, "CURRENT_INTEL", 20,
            "CURRENT_AMD", 20, "CURRENT_GPU", 3, "LEGACY_INTEL", 16, "DESKTOP_RAM", 10);
    private static final Set<String> CATEGORIES = Set.of("CPU", "MOTHERBOARD", "RAM", "STORAGE", "GPU");
    private static final Set<String> GATE_CODES = Set.of("USER_ADOPTION", "PRODUCT_IDENTITY",
            "MANUFACTURER_PART_NUMBER", "DOMESTIC_NEW_SALE", "COMPATIBILITY", "MINIMUM_BIOS",
            "STORAGE_SLOT_RULES", "SALE_UNIT", "PRICE");
    private final JsonMapper mapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();

    public CatalogExpansionPreview load(Path repositoryRoot) {
        return load(CatalogExpansionPreviewLoader.class.getClassLoader(), repositoryRoot);
    }

    CatalogExpansionPreview load(ClassLoader classLoader, Path repositoryRoot) {
        try (var input = classLoader.getResourceAsStream(RESOURCE)) {
            if (input == null) throw invalid("Bundled expansion preview is missing");
            return read(input.readNBytes(MAX_BYTES + 1), repositoryRoot);
        } catch (IOException ex) {
            throw new IllegalArgumentException("Unable to read bundled expansion preview", ex);
        }
    }

    public CatalogExpansionPreview read(byte[] bytes, Path repositoryRoot) {
        require(bytes != null && bytes.length > 0 && bytes.length <= MAX_BYTES,
                "Expansion preview JSON must contain 1 byte..5 MiB");
        final CatalogExpansionPreview preview;
        try {
            preview = mapper.treeToValue(object(mapper.readTree(bytes)), CatalogExpansionPreview.class);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("Invalid expansion preview JSON", ex);
        }
        try {
            validate(preview);
            verifyRepositorySources(preview, repositoryRoot);
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("Invalid expansion preview facts", ex);
        }
        return preview;
    }

    private void validate(CatalogExpansionPreview preview) {
        require(preview.schemaVersion() == 1 && DATE.equals(preview.previewDate()), "Unsupported expansion preview version/date");
        require("DB_FREE_PREVIEW_PENDING_PRODUCT_ADOPTION".equals(preview.stage()), "Invalid expansion preview stage");
        var auth = preview.authorization();
        require(auth != null && auth.implementationPreviewApproved() && !auth.catalogAdoptionApproved()
                && !auth.databaseApplyAuthorized() && !auth.priceCollectionAuthorized()
                && auth.requiresUserConfirmationBeforeApply(), "Expansion preview cannot grant adoption or apply authorization");
        var plan = preview.canonicalIdPlan();
        require(plan != null && "JAVA_NAME_UUID_MD5_V3".equals(plan.algorithm())
                && "pc-upgrade-lab/catalog/product/v1".equals(plan.namespace())
                && List.of("sourceName", "sourceExternalId").equals(plan.identitySeedFields())
                && !plan.nameBasedMatchingAllowed() && plan.localProductIdsPreserved()
                && "PROPOSED_MAPPING_NOT_APPLIED".equals(plan.status()), "Invalid canonical identity plan");
        require(preview.existingProductMappings() != null && preview.existingProductMappings().size() == 300,
                "Exactly 300 existing reviewed identities are required");
        var externalIds = new HashSet<String>();
        var canonicalIds = new HashSet<UUID>();
        for (var mapping : preview.existingProductMappings()) {
            require(mapping != null && "BUILDCORES".equals(mapping.sourceName())
                    && mapping.localProductId() == null && !mapping.mappingApproved()
                    && "EXACT_REVIEWED_SOURCE_IDENTITY".equals(mapping.lookupStrategy())
                    && "REJECT_AND_REVIEW".equals(mapping.typeConflictBehavior())
                    && "NEEDS_LOCAL_LOOKUP".equals(mapping.mappingReviewStatus()), "Existing mapping must preserve unqueried local IDs");
            require(mapping.sourceExternalId() != null && externalIds.add(mapping.sourceExternalId()), "Duplicate source identity");
            require(mapping.canonicalProductId() != null && canonicalIds.add(mapping.canonicalProductId())
                    && mapping.canonicalProductId().toString().equals(CanonicalCatalogIds.productForReviewedSource(
                            CatalogSourceName.BUILDCORES, mapping.sourceExternalId())), "Canonical ID does not match reviewed source identity");
            require(mapping.expectedProduct() != null && mapping.expectedProduct().category() != null
                    && mapping.expectedProduct().manufacturer() != null && mapping.expectedProduct().modelName() != null,
                    "Expected reviewed product identity is required");
        }
        require(preview.candidates() != null && preview.candidates().size() == 78, "Exactly 78 research candidates are required");
        require(preview.existingProductMappings().stream().filter(mapping -> "APPROVED_SNAPSHOT".equals(mapping.approvedPriceStatus())).count() == 72,
                "The 72 historical approved price statuses must be retained");
        var candidateKeys = new HashSet<String>();
        var groupCounts = new HashMap<String, Integer>();
        int previousGroup = -1;
        for (int index = 0; index < preview.candidates().size(); index++) {
            var candidate = preview.candidates().get(index);
            require(candidate != null && candidate.candidateKey() != null && candidateKeys.add(candidate.candidateKey()),
                    "Candidate keys must be unique");
            int groupIndex = GROUP_ORDER.indexOf(candidate.researchGroup());
            require(groupIndex >= previousGroup && groupIndex >= 0 && candidate.reviewOrder() == index + 1,
                    "Latest-first review order is invalid");
            previousGroup = groupIndex;
            groupCounts.merge(candidate.researchGroup(), 1, Integer::sum);
            require(CATEGORIES.contains(candidate.category())
                    && (groupIndex < 4 ? "LATEST_FIRST" : "FOLLOWUP").equals(candidate.reviewPhase()), "Invalid candidate type/phase");
            require("NEEDS_REVIEW".equals(candidate.eligibility()) && "NOT_APPROVED".equals(candidate.adoptionDecision())
                    && candidate.proposedCanonicalProductId() == null && candidate.localProductId() == null
                    && candidate.userConfirmationRequired() && !candidate.databaseApplyAuthorized()
                    && "NOT_COLLECTED".equals(candidate.priceStatus())
                    && "NOT_REVIEWED".equals(candidate.compatibilityReviewStatus()), "Research candidates cannot become approved products or prices");
            require(Set.of("INSTALLED_PC_REFERENCE", "PURCHASE_CANDIDATE", "BOTH").contains(candidate.role()), "Invalid research role");
            require(candidate.knownSpecificationFacts() != null && candidate.knownSpecificationFacts().isObject()
                    && candidate.unconfirmedFactPaths() != null, "Known and unknown research facts are required");
            var nulls = new ArrayList<String>();
            findNullPaths(candidate.knownSpecificationFacts(), "specification", nulls);
            require(nulls.equals(candidate.unconfirmedFactPaths()), "Explicit unknown facts must be preserved");
            validateUnits(candidate);
            validateGates(candidate);
            validateSources(candidate.officialSources(), true);
            validateSources(candidate.domesticSources(), false);
        }
        require(GROUP_COUNTS.equals(groupCounts), "Research group counts are invalid");
        require(preview.heldAdditionalModels() != null && preview.heldAdditionalModels().size() == 6, "Six AI desktop models must remain held");
        var heldNames = new HashSet<String>();
        for (var held : preview.heldAdditionalModels()) {
            require(held != null && heldNames.add(held.modelName()) && "NEEDS_REVIEW".equals(held.eligibility())
                    && held.proposedCanonicalProductId() == null && held.localProductId() == null
                    && held.userConfirmationRequired() && !held.databaseApplyAuthorized()
                    && "NOT_COLLECTED".equals(held.priceStatus())
                    && "HOLD_RETAIL_AND_BIOS_NOT_VERIFIED".equals(held.status()), "AI retail/BIOS holds cannot be promoted");
            validateSources(held.officialSources(), true);
        }
        var summary = preview.summary();
        require(summary != null && summary.existingProductCount() == 300 && summary.retainedApprovedPriceCount() == 72
                && summary.researchCandidateCount() == 78 && summary.latestFirstCandidateCount() == 52
                && summary.followupCandidateCount() == 26 && summary.heldAdditionalModelCount() == 6
                && summary.partNumberUnknownCount() == preview.candidates().stream().filter(c -> c.researchPartNumber() == null).count()
                && summary.newProductsCreated() == 0 && summary.newPriceObservationsCreated() == 0, "Invalid preview summary");
    }

    private void validateGates(CatalogExpansionPreview.Candidate candidate) {
        require(candidate.reviewGates() != null && candidate.reviewGates().size() == GATE_CODES.size(), "All review gates are required");
        var gates = new HashMap<String, String>();
        for (var gate : candidate.reviewGates()) {
            require(gate != null && gate.code() != null && gate.detail() != null && !gate.detail().isBlank()
                    && gates.putIfAbsent(gate.code(), gate.status()) == null, "Duplicate/missing review gate");
        }
        require(gates.keySet().equals(GATE_CODES), "Unknown/missing review gate");
        require("PENDING".equals(gates.get("USER_ADOPTION")) && "PENDING".equals(gates.get("PRODUCT_IDENTITY"))
                && (candidate.researchPartNumber() == null ? "UNKNOWN" : "RESEARCH_RECORDED_UNAPPROVED")
                        .equals(gates.get("MANUFACTURER_PART_NUMBER"))
                && "NOT_VERIFIED".equals(gates.get("DOMESTIC_NEW_SALE")) && "NOT_REVIEWED".equals(gates.get("COMPATIBILITY"))
                && "NOT_REVIEWED".equals(gates.get("SALE_UNIT")) && "NOT_COLLECTED".equals(gates.get("PRICE")), "Review gates cannot imply approval");
        require((Set.of("CPU", "MOTHERBOARD", "STORAGE").contains(candidate.category()) ? "NOT_REVIEWED" : "NOT_APPLICABLE_TO_RESEARCH_TYPE")
                .equals(gates.get("MINIMUM_BIOS")), "Minimum BIOS review must remain explicit");
        require((Set.of("MOTHERBOARD", "STORAGE").contains(candidate.category()) ? "NOT_REVIEWED" : "NOT_APPLICABLE_TO_RESEARCH_TYPE")
                .equals(gates.get("STORAGE_SLOT_RULES")), "Slot review must remain explicit");
    }

    private void validateUnits(CatalogExpansionPreview.Candidate candidate) {
        JsonNode spec = candidate.knownSpecificationFacts();
        require(candidate.unitValidation() != null && candidate.unitValidation().note() != null, "Unit validation is required");
        if ("STORAGE".equals(candidate.category())) {
            long gb = positiveInteger(spec.get("advertisedCapacityGb"));
            long bytes = positiveInteger(spec.get("capacityBytes"));
            require("DECIMAL_GB".equals(text(spec, "capacityBasis"))
                    && bytes == Math.multiplyExact(gb, 1_000_000_000L), "SSD advertised capacity must use decimal bytes");
            if ("SATA".equals(text(spec, "busInterface"))) {
                for (String field : List.of("pcieVersion", "pcieLanes", "nvmeVersion")) {
                    require(spec.get(field) != null && spec.get(field).isNull(), "SATA PCIe/NVMe fields must remain not applicable");
                }
            } else {
                require("PCIE".equals(text(spec, "busInterface")) && "NVME".equals(text(spec, "interfaceProtocol"))
                        && positiveInteger(spec.get("pcieLanes")) > 0 && spec.get("pcieVersion") != null, "Invalid PCIe/NVMe research specification");
            }
            require("DECIMAL_GB".equals(candidate.unitValidation().capacityBasis()), "Invalid SSD unit label");
        } else if ("RAM".equals(candidate.category())) {
            require(positiveInteger(spec.get("moduleCount")) == 1
                    && positiveInteger(spec.get("moduleCapacityBytes")) % (1L << 30) == 0,
                    "RAM must retain one-module binary capacity; it is not a sale kit");
            require("BINARY_MODULE_BYTES".equals(candidate.unitValidation().capacityBasis()), "Invalid RAM unit label");
        } else if ("GPU".equals(candidate.category())) {
            require(positiveInteger(spec.get("vramBytes")) % (1L << 30) == 0, "GPU VRAM bytes must retain research units");
            require("BINARY_VRAM_BYTES".equals(candidate.unitValidation().capacityBasis()), "Invalid GPU unit label");
        } else {
            require(candidate.unitValidation().capacityBasis() == null, "CPU/board GB values must not be inferred as device bytes");
        }
        require((Set.of("STORAGE", "RAM", "GPU").contains(candidate.category())
                ? "RECORDED_UNITS_VALIDATED" : "NO_RECORDED_DEVICE_CAPACITY_TO_CONVERT")
                .equals(candidate.unitValidation().status()), "Unit checks are not product adoption or compatibility approval");
    }

    private void verifyRepositorySources(CatalogExpansionPreview preview, Path repositoryRoot) {
        require(repositoryRoot != null && preview.sourceHashes() != null, "Repository sources are required for hash verification");
        final Path root;
        try { root = repositoryRoot.toRealPath(); }
        catch (IOException ex) { throw new IllegalArgumentException("Repository source root is unavailable", ex); }
        var documents = new HashMap<String, JsonNode>();
        var hashes = new HashMap<String, String>();
        for (var source : preview.sourceHashes()) {
            require(source != null && source.file() != null && source.normalizedSha256() != null
                    && source.normalizedSha256().matches("[0-9a-f]{64}"), "Invalid source hash");
            require(source.file().matches("^(data/catalog-(review|current-prices)/|backend/src/main/resources/catalog/(seed|enrichment)/)[A-Za-z0-9_./-]+\\.json$")
                    && !List.of(source.file().split("/")).contains(".."), "Only public reviewed catalog JSON sources may be read");
            require(hashes.putIfAbsent(source.file(), source.normalizedSha256()) == null, "Duplicate source hash path");
            try {
                Path file = root.resolve(source.file()).toRealPath();
                require(file.startsWith(root), "Reviewed source is outside the repository");
                byte[] raw;
                try (var input = Files.newInputStream(file)) { raw = input.readNBytes(MAX_BYTES + 1); }
                require(raw.length <= MAX_BYTES, "Reviewed source exceeds 5 MiB");
                String normalized = new String(raw, StandardCharsets.UTF_8).replace("\r\n", "\n");
                require(sha256(normalized).equals(source.normalizedSha256()), "Reviewed source hash mismatch: " + source.file());
                documents.put(source.file(), object(mapper.readTree(raw)));
            } catch (IOException ex) {
                throw new IllegalArgumentException("Unable to read reviewed catalog source: " + source.file(), ex);
            }
        }
        JsonNode research = documents.get(RESEARCH_FILE);
        JsonNode existing = documents.get(EXISTING_FILE);
        require(research != null && existing != null, "Aggregate review sources are required");
        require(DATE.equals(text(research, "researchDate")) && DATE.equals(text(existing, "auditDate"))
                && existing.get("summary").get("approvedPriceCount").intValue() == 72,
                "Reviewed source date/approved historical price count changed");
        verifyDeclaredHashes(research, hashes);
        verifyDeclaredHashes(existing, hashes);
        var rawCandidates = new HashMap<String, JsonNode>();
        var rawHeld = new HashMap<String, JsonNode>();
        var rawCandidateFiles = new HashMap<String, String>();
        var rawHeldFiles = new HashMap<String, String>();
        for (JsonNode source : research.get("sources")) {
            String sourceFile = text(source, "file");
            JsonNode group = documents.get(sourceFile);
            require(group != null, "Research group source is missing");
            for (JsonNode item : group.get("candidates")) {
                require(rawCandidates.putIfAbsent(text(item, "candidateKey"), item) == null, "Duplicate raw candidate identity");
                rawCandidateFiles.put(text(item, "candidateKey"), sourceFile);
            }
            if (group.get("heldProducts") != null) for (JsonNode held : group.get("heldProducts")) {
                require(rawHeld.putIfAbsent(text(held, "modelName"), held) == null, "Duplicate held model identity");
                rawHeldFiles.put(text(held, "modelName"), sourceFile);
            }
        }
        require(rawCandidates.size() == 78 && rawHeld.size() == 6, "Raw research counts changed");
        for (var candidate : preview.candidates()) {
            JsonNode source = rawCandidates.get(candidate.candidateKey());
            require(source != null, "Research candidate is not in its source");
            equal(candidate.researchSourceFile(), rawCandidateFiles.get(candidate.candidateKey()));
            equal(candidate.category(), text(source, "category"));
            equal(candidate.manufacturer(), text(source, "manufacturer"));
            equal(candidate.modelName(), text(source, "modelName"));
            equal(candidate.researchPartNumber(), nullableText(source, "partNumber"));
            equal(candidate.role(), text(source, "role"));
            equal(candidate.selectionReason(), text(source, "reasonForSelection"));
            equal(candidate.researchReadiness(), text(source, "readiness"));
            equal(candidate.lifeCycleStatus(), nullableText(source, "lifeCycleStatus"));
            equal(candidate.lifeCycleDateRaw(), nullableText(source, "lifeCycleDateRaw"));
            equal(candidate.saleIdentityStatus(), text(source, "saleIdentityStatus"));
            equal(candidate.knownSpecificationFacts(), source.get("specification"));
            equal(candidate.selectionEvidence(), source.get("selectionEvidence"));
            equal(mapper.valueToTree(candidate.officialSources()), source.get("officialSources"));
            equal(mapper.valueToTree(candidate.domesticSources()), source.get("domesticSources"));
            equal(mapper.valueToTree(candidate.compatibilityNotes()), source.get("compatibilityNotes"));
            equal(mapper.valueToTree(candidate.identificationNotes()), source.get("identificationNotes"));
            equal(mapper.valueToTree(candidate.openQuestions()), source.get("openQuestions"));
            require(documents.containsKey(candidate.researchSourceFile())
                    && candidate.researchGroup().equals(text(documents.get(candidate.researchSourceFile()), "categoryGroup")), "Candidate group/source mismatch");
        }
        var reviewedProducts = new HashMap<String, JsonNode>();
        for (JsonNode item : existing.get("items")) require(reviewedProducts.putIfAbsent(text(item, "externalId"), item) == null,
                "Duplicate existing reviewed identity");
        require(reviewedProducts.size() == 300, "Existing catalog count changed");
        for (var mapping : preview.existingProductMappings()) {
            JsonNode item = reviewedProducts.get(mapping.sourceExternalId());
            require(item != null, "Mapping is not a reviewed existing source identity");
            JsonNode product = item.get("product");
            equal(mapping.expectedProduct().category(), text(product, "type"));
            equal(mapping.expectedProduct().manufacturer(), text(product, "manufacturer"));
            equal(mapping.expectedProduct().modelName(), text(product, "modelName"));
            equal(mapping.expectedProduct().partNumber(), nullableText(product, "partNumber"));
            JsonNode evidence = item.get("sourceEvidence");
            equal(mapping.sourceManifestFile(), text(evidence, "manifestPath"));
            equal(mapping.sourceRecordSha256(), text(evidence, "rawSha256"));
            equal(mapping.approvedPriceStatus(), text(item.get("priceEvidence"), "status"));
            String rawFile = "backend/src/main/resources/catalog/seed/" + text(evidence, "seedName")
                    + "/buildcores/" + mapping.sourceExternalId() + ".json";
            equal(mapping.sourceRecordSha256(), hashes.get(rawFile));
            JsonNode manifest = documents.get(mapping.sourceManifestFile());
            require(manifest != null, "Reviewed manifest is missing");
            JsonNode manifestItem = null;
            for (JsonNode row : manifest.get("items")) if (mapping.sourceExternalId().equals(text(row, "externalId"))) {
                require(manifestItem == null, "Duplicate manifest identity");
                manifestItem = row;
            }
            require(manifestItem != null, "Reviewed identity is not in its manifest");
            equal(product, manifestItem.get("product"));
            equal(item.get("specification"), manifestItem.get("specification"));
            equal(mapping.sourceRecordSha256(), text(manifestItem, "rawSha256"));
        }
        for (var held : preview.heldAdditionalModels()) {
            JsonNode source = rawHeld.get(held.modelName());
            require(source != null, "Held model is not in its raw source");
            equal(held.researchSourceFile(), rawHeldFiles.get(held.modelName()));
            equal(held.researchGroup(), text(documents.get(held.researchSourceFile()), "categoryGroup"));
            equal(held.status(), text(source, "status"));
            equal(held.reasonForHold(), text(source, "reasonForHold"));
            equal(held.category(), text(source, "category"));
            equal(held.manufacturer(), text(source, "manufacturer"));
            for (String field : List.of("processorSeries", "socketCode", "coreCount", "threadCount", "tdpW")) {
                equal(held.knownSpecificationFacts().get(field), source.get(field));
            }
            equal(mapper.valueToTree(held.officialSources()), source.get("officialSources"));
            equal(mapper.valueToTree(held.openQuestions()), source.get("openQuestions"));
        }
    }

    private static void verifyDeclaredHashes(JsonNode document, Map<String, String> hashes) {
        require(document.get("sources") != null && document.get("sources").isArray(), "Source provenance is missing");
        for (JsonNode source : document.get("sources")) equal(text(source, "normalizedSha256"), hashes.get(text(source, "file")));
    }

    private static void validateSources(List<JsonNode> sources, boolean official) {
        require(sources != null && (!official || !sources.isEmpty()), "Source evidence is required");
        for (JsonNode source : sources) {
            require(text(source, "url").startsWith("https://") && DATE.equals(text(source, "checkedDate")), "Invalid source URL/check date");
            require(!text(source, "title").isBlank(), "Source title is required");
            if (official) require(source.get("supportedFacts") != null && source.get("supportedFacts").isArray()
                    && !source.get("supportedFacts").isEmpty(), "Official supported facts are required");
        }
    }

    private static void findNullPaths(JsonNode value, String prefix, List<String> output) {
        if (value.isNull()) output.add(prefix);
        else if (value.isArray()) for (int index = 0; index < value.size(); index++) {
            findNullPaths(value.get(index), prefix + "." + index, output);
        }
        else if (value.isObject()) for (var entry : value.properties()) {
            findNullPaths(entry.getValue(), prefix + "." + entry.getKey(), output);
        }
    }

    private static long positiveInteger(JsonNode value) {
        require(value != null && value.isIntegralNumber(), "Recorded device quantities must be integers");
        long number;
        try { number = value.bigIntegerValue().longValueExact(); }
        catch (ArithmeticException ex) { throw invalid("Recorded device quantity exceeds long range"); }
        require(number > 0, "Recorded device quantities must be positive");
        return number;
    }

    private static JsonNode object(JsonNode node) { require(node != null && node.isObject(), "JSON object is required"); return node; }
    private static String text(JsonNode node, String field) {
        require(node != null && node.get(field) != null && node.get(field).isString()
                && !node.get(field).stringValue().isBlank(), "Required source text is missing: " + field);
        return node.get(field).stringValue();
    }
    private static String nullableText(JsonNode node, String field) {
        return node.get(field) == null || node.get(field).isNull() ? null : text(node, field);
    }
    private static String sha256(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 is unavailable", ex); }
    }
    /** JSON.stringify changes 95.0 to 95; compare exact numeric values without type coercion. */
    static boolean sameJsonFacts(JsonNode left, JsonNode right) {
        if (left == null || right == null) return false;
        if (left.isNumber() || right.isNumber()) {
            return left.isNumber() && right.isNumber() && left.decimalValue().compareTo(right.decimalValue()) == 0;
        }
        if (left.isObject() || right.isObject()) {
            if (!left.isObject() || !right.isObject() || left.size() != right.size()) return false;
            for (var entry : left.properties()) {
                if (!sameJsonFacts(entry.getValue(), right.get(entry.getKey()))) return false;
            }
            return true;
        }
        if (left.isArray() || right.isArray()) {
            if (!left.isArray() || !right.isArray() || left.size() != right.size()) return false;
            for (int index = 0; index < left.size(); index++) {
                if (!sameJsonFacts(left.get(index), right.get(index))) return false;
            }
            return true;
        }
        return left.equals(right);
    }

    private static void equal(Object left, Object right) {
        boolean same = left instanceof JsonNode leftJson && right instanceof JsonNode rightJson
                ? sameJsonFacts(leftJson, rightJson) : Objects.equals(left, right);
        require(same, "Preview differs from reviewed source facts");
    }
    private static void require(boolean condition, String message) { if (!condition) throw invalid(message); }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
