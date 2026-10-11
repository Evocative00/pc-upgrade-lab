package com.pcupgradelab.catalog.powercase;

import com.pcupgradelab.catalog.shared.RetailCatalogApplyLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** 현재 미승인 조사 자료를 보존한다. 별도 정확한 승인 문서만 실제 적용 계획을 만들 수 있다. */
public final class PowerCaseCatalogImportLoader {
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(tools.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .enable(tools.jackson.databind.cfg.EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS).build();

    public record ApprovedObservation(String canonicalId, String sourceName, String externalId,
                                      Long amountKrw, Instant observedAt) { }
    public record Approval(Integer schemaVersion, String decision, Boolean databaseApplyApproved,
            Boolean publicationApproved, String previewFile, String previewSha256, List<String> canonicalIds,
            Map<String, Integer> registrationRows, Map<String, Integer> priceRows,
            List<ApprovedObservation> priceObservations, Integer modelWrites, Boolean autoActivation) { }
    public static final class Plan {
        private final PowerCaseCatalogPreviewLoader.Plan preview;
        private final RetailCatalogApplyLoader.Plan baseline;
        private final String previewSha256;
        private final boolean databaseApplyApproved;
        private Plan(PowerCaseCatalogPreviewLoader.Plan preview, RetailCatalogApplyLoader.Plan baseline,
                     String previewSha256, boolean approved) {
            this.preview = preview; this.baseline = baseline; this.previewSha256 = previewSha256; databaseApplyApproved = approved;
        }
        public PowerCaseCatalogPreviewLoader.Plan preview() { return preview; }
        public RetailCatalogApplyLoader.Plan baseline() { return baseline; }
        public String previewSha256() { return previewSha256; }
        public boolean databaseApplyApproved() { return databaseApplyApproved; }
    }

    public Plan loadForCheck(Path repositoryRoot) { return load(repositoryRoot, null); }
    public Plan loadApproved(Path repositoryRoot, Path approvalFile) {
        if (approvalFile == null) throw new IllegalArgumentException("Separate --approval-file is required before database application");
        return load(repositoryRoot, approvalFile);
    }
    public Approval approvalProposal(Plan plan) { return approval(plan, false); }

    private Plan load(Path repositoryRoot, Path approvalFile) {
        try {
            Path root = repositoryRoot.toRealPath();
            var preview = new PowerCaseCatalogPreviewLoader().load(root);
            String previewText = Files.readString(root.resolve(PowerCaseCatalogPreviewLoader.FILE), StandardCharsets.UTF_8);
            if (previewText.startsWith("\uFEFF")) previewText = previewText.substring(1);
            // Match the shared stage: UTF-8 without BOM, CRLF normalized to LF; all other content is preserved.
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(previewText.replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8)));
            // Existing 300 + seven pilot products + the previously approved ASUS sale product.
            var baseline = new RetailCatalogApplyLoader().load(root);
            var plan = new Plan(preview, baseline, hash, approvalFile != null);
            if (approvalFile != null) {
                Path path = approvalFile.isAbsolute() ? approvalFile.normalize() : root.resolve(approvalFile).normalize();
                require(path.startsWith(root) && path.toRealPath().startsWith(root), "Approval file must stay inside the repository");
                for (Path current = path; !current.equals(root); current = current.getParent())
                    require(!Files.isSymbolicLink(current), "Approval file must not use symlinks");
                require(Files.isRegularFile(path) && Files.size(path) <= 1024 * 1024, "Approval file is missing or too large");
                String text = Files.readString(path, StandardCharsets.UTF_8);
                if (text.startsWith("\uFEFF")) text = text.substring(1);
                var accepted = MAPPER.treeToValue(MAPPER.readTree(text), Approval.class);
                require(approval(plan, true).equals(accepted),
                        "Exact separate database approval, normalized preview hash, seven identities and reviewed price scope are required");
            }
            return plan;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid PSU/CASE import plan: " + ex.getMessage(), ex);
        }
    }

    private Approval approval(Plan plan, boolean approved) {
        if (plan == null) throw new IllegalArgumentException("Validated import plan is required");
        var observations = plan.preview().prices().stream().map(p -> new ApprovedObservation(p.canonicalId(),
                p.offer().sourceName(), p.offer().externalId(), p.price().amountKrw(), p.price().observedAt())).toList();
        return new Approval(1, approved ? "APPROVED" : "PENDING", approved, false, PowerCaseCatalogPreviewLoader.FILE,
                plan.previewSha256(), plan.preview().registrations().stream().map(PowerCaseCatalogPreviewLoader.Registration::canonicalId).toList(),
                plan.preview().report().proposedRegistrationRows(), plan.preview().report().proposedPriceRows(), observations, 0, false);
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
}
