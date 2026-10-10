package com.pcupgradelab.catalog.pilot;

import com.pcupgradelab.catalog.CatalogProductCreateRequest;
import com.pcupgradelab.catalog.CatalogSourceInput;
import com.pcupgradelab.catalog.CatalogSourceName;
import com.pcupgradelab.catalog.CatalogSpecification;
import com.pcupgradelab.catalog.identity.CatalogIdentityKind;
import com.pcupgradelab.catalog.identity.CatalogIdentityRequests;
import com.pcupgradelab.catalog.identity.CatalogRole;
import com.pcupgradelab.catalog.memory.CpuMemorySupport;
import com.pcupgradelab.catalog.price.CatalogPriceImportBatch;
import com.pcupgradelab.catalog.price.CatalogPriceImportLoader;
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
import java.util.Objects;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;

/** Converts the validated public review into domain inputs; no database, private settings or network. */
public final class CatalogPilotImportLoader {
    public static final String PRICES_FILE = "data/catalog-review/pilot-approved-prices-2026-10-10.json";
    public static final String APPROVAL_FILE = "data/catalog-review/pilot-apply-approval-2026-10-10.json";
    private static final int MAX_BYTES = 5 * 1024 * 1024;
    private final JsonMapper mapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    private final JsonMapper approvalMapper = mapper.rebuild()
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES).build();

    public Plan load(Path repositoryRoot) {
        try { return loadReviewedPlan(repositoryRoot); }
        catch (IllegalArgumentException ex) { throw ex; }
        catch (RuntimeException ex) { throw new IllegalArgumentException("Invalid approved pilot inputs or typed facts", ex); }
    }

    private Plan loadReviewedPlan(Path repositoryRoot) {
        Path root = realRoot(repositoryRoot);
        byte[] previewBytes = readPublicFile(root, CatalogPilotPreviewLoader.PREVIEW_FILE);
        // Validate the same captured bytes before converting them; never bypass source hash/domain checks.
        new CatalogPilotPreviewLoader().read(previewBytes, root);
        byte[] priceBytes = readPublicFile(root, PRICES_FILE);
        validateApproval(root, previewBytes, priceBytes);
        var preview = mapper.treeToValue(mapper.readTree(normalizedUtf8(previewBytes)), CatalogPilotPreviewLoader.Preview.class);
        var models = preview.models().stream().map(CatalogPilotPreviewLoader.Model::registration).toList();
        var modelIds = new HashMap<String, String>();
        preview.models().forEach(model -> modelIds.put(model.modelKey(), model.registration().canonicalId()));
        var parts = new ArrayList<Part>();
        for (var product : preview.products()) {
            parts.add(new Part(product.selectionNumber(), product.sourceIdentity(), product.proposedCanonicalId(),
                    product.product(), specification(product.product().type(), product.specification()),
                    product.manufacturerEvidence(), product.modelKey(), modelIds.get(product.modelKey()),
                    product.identityKind(), product.role(), product.bindingReviewScope(),
                    nullable(product.memorySupport(), CpuMemorySupport.class),
                    nullable(product.storageSupport(), MotherboardStorageSupport.class)));
        }
        var pairs = new ArrayList<CpuSupportPair>();
        for (JsonNode pair : preview.cpuSupportPairs()) {
            pairs.add(new CpuSupportPair(pair.path("boardSelectionNumber").intValue(),
                    pair.path("cpuSelectionNumber").intValue(), pair.path("entry").deepCopy()));
        }
        var prices = new CatalogPriceImportLoader().read(priceBytes);
        validatePriceScope(parts, preview, prices);
        return new Plan(models, parts, pairs, prices);
    }

    private void validateApproval(Path root, byte[] previewBytes, byte[] priceBytes) {
        var approval = approvalMapper.treeToValue(approvalMapper.readTree(
                normalizedUtf8(readPublicFile(root, APPROVAL_FILE))), Approval.class);
        require(approval.schemaVersion() == 1 && "2026-10-10".equals(approval.approvalDate())
                && "카탈로그 보강과 8종 가격 반영 승인".equals(approval.decision()), "Explicit pilot approval is required");
        require(CatalogPilotPreviewLoader.PREVIEW_FILE.equals(approval.previewFile())
                && PRICES_FILE.equals(approval.pricesFile()), "Approval must reference the exact public pilot files");
        require(sha256(normalizedUtf8(previewBytes)).equals(approval.previewNormalizedSha256())
                && sha256(normalizedUtf8(priceBytes)).equals(approval.pricesNormalizedSha256()),
                "Approved preview or prices hash mismatch; review the concrete changed scope again");
        var scope = approval.scope();
        require(scope != null && scope.newProducts() == 7 && scope.modelRecords() == 13
                && scope.productBindings() == 14 && scope.storageProfiles() == 4 && scope.storageSlots() == 19
                && scope.priceObservations() == 8 && !scope.autoActivation() && !scope.githubPublish(),
                "Pilot approval must preserve the exact catalog, price and activation scope");
    }

    private void validatePriceScope(List<Part> parts, CatalogPilotPreviewLoader.Preview preview, CatalogPriceImportBatch batch) {
        require(batch.items().size() == 8, "Exactly eight approved pilot price items are required");
        var expectedExisting = new HashSet<CatalogPriceImportBatch.Item>();
        for (var product : preview.products()) {
            JsonNode item = product.priceReview().get("priceImportItem");
            if (item != null && !item.isNull()) expectedExisting.add(mapper.treeToValue(item, CatalogPriceImportBatch.Item.class));
        }
        require(expectedExisting.size() == 6, "Six unchanged existing price items are required");
        var seen = new HashSet<String>();
        int existingPrices = 0;
        int storagePrices = 0;
        for (var item : batch.items()) {
            String key = item.product().sourceName() + "/" + item.product().externalId();
            require(seen.add(key), "Duplicate approved price product identity");
            Part part = parts.stream().filter(candidate -> candidate.sourceIdentity().sourceName() == item.product().sourceName()
                    && candidate.sourceIdentity().externalId().equals(item.product().externalId())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Price does not belong to an approved pilot product"));
            require(part.product().manufacturer().equals(item.product().manufacturer())
                    && part.product().modelName().equals(item.product().modelName())
                    && Objects.equals(part.product().partNumber(), item.product().partNumber()), "Approved price product identity differs");
            if (item.product().sourceName() == CatalogSourceName.BUILDCORES) {
                require(expectedExisting.contains(item), "Existing reviewed price payload changed");
                existingPrices++;
            } else {
                require(item.product().sourceName() == CatalogSourceName.MANUFACTURER
                        && part.selectionNumber() >= 13 && part.product().type() == PartType.STORAGE
                        && item.offer().saleUnit() == CatalogPriceImportBatch.SaleUnit.PRODUCT
                        && item.offer().moduleCount() == null, "Only the two exact SSD sales variants may add prices");
                storagePrices++;
            }
        }
        require(existingPrices == 6 && storagePrices == 2, "Price scope must remain six existing and two exact SSD variants");
    }

    private CatalogSpecification specification(PartType type, JsonNode node) {
        return switch (type) {
            case CPU -> mapper.treeToValue(node, CatalogSpecification.Cpu.class);
            case MOTHERBOARD -> mapper.treeToValue(node, CatalogSpecification.Motherboard.class);
            case RAM -> mapper.treeToValue(node, CatalogSpecification.Ram.class);
            case GPU -> mapper.treeToValue(node, CatalogSpecification.Gpu.class);
            case STORAGE -> mapper.treeToValue(node, CatalogSpecification.Storage.class);
            default -> throw new IllegalArgumentException("Unsupported pilot specification type");
        };
    }

    private <T> T nullable(JsonNode node, Class<T> type) {
        return node == null || node.isNull() ? null : mapper.treeToValue(node, type);
    }

    private static Path realRoot(Path root) {
        require(root != null, "Repository root is required");
        try { return root.toRealPath(); }
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

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    public record Plan(List<CatalogIdentityRequests.ModelRegistration> models, List<Part> parts,
                       List<CpuSupportPair> cpuSupportPairs, CatalogPriceImportBatch prices) {
        public Plan {
            require(models != null && parts != null && cpuSupportPairs != null && prices != null, "Complete pilot plan is required");
            models = List.copyOf(models); parts = List.copyOf(parts); cpuSupportPairs = List.copyOf(cpuSupportPairs);
        }
    }

    public record Part(int selectionNumber, CatalogPilotPreviewLoader.SourceIdentity sourceIdentity,
                       String proposedCanonicalId, CatalogProductCreateRequest product, CatalogSpecification specification,
                       CatalogSourceInput manufacturerEvidence, String modelKey, String modelCanonicalId,
                       CatalogIdentityKind identityKind, CatalogRole role, String bindingReviewScope,
                       CpuMemorySupport memorySupport, MotherboardStorageSupport storageSupport) { }

    public record CpuSupportPair(int boardSelectionNumber, int cpuSelectionNumber, JsonNode seedEntryJson) { }

    private record Approval(int schemaVersion, String approvalDate, String decision, String previewFile,
                            String previewNormalizedSha256, String pricesFile, String pricesNormalizedSha256,
                            ApprovalScope scope) { }
    private record ApprovalScope(int newProducts, int modelRecords, int productBindings, int storageProfiles,
                                 int storageSlots, int priceObservations, boolean autoActivation, boolean githubPublish) { }
}
