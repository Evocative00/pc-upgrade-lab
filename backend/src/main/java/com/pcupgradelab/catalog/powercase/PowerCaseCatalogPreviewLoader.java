package com.pcupgradelab.catalog.powercase;

import com.pcupgradelab.catalog.CatalogEntryCreateRequest;
import com.pcupgradelab.catalog.CatalogProductCreateRequest;
import com.pcupgradelab.catalog.CatalogSourceInput;
import com.pcupgradelab.catalog.CatalogSourceName;
import com.pcupgradelab.catalog.CatalogSpecification;
import com.pcupgradelab.catalog.CatalogVerificationStatus;
import com.pcupgradelab.catalog.identity.CanonicalCatalogIds;
import com.pcupgradelab.catalog.identity.CatalogIdentityKind;
import com.pcupgradelab.catalog.identity.CatalogRole;
import com.pcupgradelab.catalog.price.CatalogPriceImportBatch;
import com.pcupgradelab.catalog.shared.SharedPriceSourcePolicy;
import com.pcupgradelab.pc.PartType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Objects;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 승인한 파워 4종·케이스 3종의 공개 조사 자료만 검증한다. Spring·DB·HTTP·개인 설정을 사용하지 않는다. */
public final class PowerCaseCatalogPreviewLoader {
    public static final String FILE = "data/catalog-review/power-case-pilot-2026-10-11.json";
    public static final String MODE = "PREVIEW_ONLY";
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(tools.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .enable(tools.jackson.databind.cfg.EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS).build();
    private record Scope(PartType type, String manufacturer, String modelName, String partNumber, String externalId) { }
    private static final Map<String, Scope> SCOPE = Map.of(
            "psu-msi-mag-a650bn", new Scope(PartType.PSU, "MSI", "MAG A650BN", null, "MAG A650BN"),
            "psu-msi-mag-a750gl-pcie5-ii", new Scope(PartType.PSU, "MSI", "MAG A750GL PCIE5 II", null, "MAG A750GL PCIE5 II"),
            "psu-msi-mag-a850gl-pcie5-ii", new Scope(PartType.PSU, "MSI", "MAG A850GL PCIE5 II", null, "MAG A850GL PCIE5 II"),
            "psu-corsair-sf1000-cp-9020257-kr", new Scope(PartType.PSU, "Corsair", "SF1000 Platinum (2024)", "CP-9020257-KR", "CP-9020257-KR"),
            "case-fractal-pop-air-black-solid-fd-c-poa1a-01", new Scope(PartType.CASE, "Fractal Design", "Pop Air Black Solid", "FD-C-POA1A-01", "FD-C-POA1A-01"),
            "case-asus-prime-ap201-mesh-black-90dc00g0-b39000", new Scope(PartType.CASE, "ASUS", "Prime AP201 Mesh Black", "90DC00G0-B39000", "90DC00G0-B39000"),
            "case-cooler-master-nr200-black-mcb-nr200-knnn-s00", new Scope(PartType.CASE, "Cooler Master", "MasterBox NR200 Black", "MCB-NR200-KNNN-S00", "MCB-NR200-KNNN-S00"));

    public record SourceReview(CatalogSourceName sourceName, String sourceUrl, Instant retrievedAt,
                               String externalId, String sourceRevision, List<String> notes) { }
    public record CurrentPrice(Long amountKrw, String sourceName, String sourceUrl, Instant observedAt) { }
    public record PriceReview(String status, Boolean exactVariantVerified, String saleCondition, Boolean inStock,
                              String offerExternalId, String saleSku, Instant verifiedAt,
                              List<String> identityEvidence, List<String> notes, List<String> sourceUrls) { }
    public record ProductReview(String key, PartType type, String manufacturer, String modelName, String partNumber,
                                CatalogIdentityKind identityKind, String canonicalId, JsonNode specification,
                                List<SourceReview> sources, CurrentPrice currentPrice, PriceReview priceReview) { }
    public record Review(Integer schemaVersion, Boolean publicationApproved, Boolean databaseApplyApproved,
                         Instant reviewedAt, List<ProductReview> products) { }
    public record SourceIdentity(CatalogSourceName sourceName, String externalId) { }
    public record Registration(String key, String canonicalId, CatalogIdentityKind identityKind, CatalogRole role,
                               CatalogVerificationStatus verificationStatus, boolean active,
                               CatalogEntryCreateRequest entry) { }
    /** 아직 적용 프로그램의 입력이 아니다. PN 미공표 제품의 정확 변형 근거도 명시하는 제안서다. */
    public record ProposedPrice(String key, String canonicalId, CatalogProductCreateRequest product,
                                SourceIdentity manufacturerIdentity, CatalogPriceImportBatch.Offer offer,
                                CatalogPriceImportBatch.Price price, List<String> identityEvidence) { }
    public record ProposedIdentity(String canonicalId, PartType type, String manufacturer, String modelName,
                                   String partNumber, String modelId, CatalogIdentityKind identityKind, CatalogRole role,
                                   CatalogVerificationStatus verificationStatus, boolean active, SourceIdentity sourceIdentity) { }
    public record Proposal<T>(int schemaVersion, String mode, boolean databaseApplyApproved,
                             boolean publicationApproved, List<T> items) { }
    public record CentralExtensionProposal(int schemaVersion, String mode, boolean databaseApplyApproved,
            boolean publicationApproved, String sourceFile, List<ProposedIdentity> products, List<ProposedPrice> prices) { }
    public record ProductReport(String key, String canonicalId, PartType type, List<String> unknownFields,
                                List<String> conditionsRequiringReview, String priceStatus, boolean priceCandidate,
                                List<String> heldReasons) { }
    public record Report(int schemaVersion, String mode, boolean databaseApplyApproved, boolean publicationApproved,
                         int databaseAccess, int databaseWrites, int publications, String requiredMigration,
                         Map<String, Integer> productsByType, Map<String, Integer> proposedRegistrationRows,
                         Map<String, Integer> proposedPriceRows, int priceCandidates, int priceHeld,
                         List<ProductReport> products, List<String> limitations) { }
    public record Plan(Review review, List<Registration> registrations, List<ProposedPrice> prices,
                       CentralExtensionProposal centralExtension, Report report) { }

    public Plan load(Path repositoryRoot) {
        try { return read(repositoryRoot); }
        catch (IOException | RuntimeException ex) {
            throw new IllegalArgumentException("Invalid PSU/CASE preview: " + ex.getMessage(), ex);
        }
    }

    private Plan read(Path repositoryRoot) throws IOException {
        Path root = repositoryRoot.toRealPath();
        Path file = root.resolve(FILE).normalize();
        require(file.startsWith(root) && file.toRealPath().startsWith(root), "Review must stay inside the repository");
        for (Path path = file; !path.equals(root); path = path.getParent())
            require(!Files.isSymbolicLink(path), "Public review must not use symlinks");
        require(Files.isRegularFile(file) && Files.size(file) <= 5 * 1024 * 1024, "Review file is missing or too large");
        String text = Files.readString(file, StandardCharsets.UTF_8);
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        var review = MAPPER.treeToValue(MAPPER.readTree(text), Review.class);
        require(Integer.valueOf(1).equals(review.schemaVersion())
                && Boolean.FALSE.equals(review.publicationApproved()) && Boolean.FALSE.equals(review.databaseApplyApproved()),
                "This workflow allows preview only; application and publication approval must remain false");
        timestamp(review.reviewedAt(), "reviewedAt");
        require(review.products() != null && review.products().size() == 7
                && review.products().stream().allMatch(Objects::nonNull), "Exactly four PSU and three CASE candidates are required");
        var registrations = new ArrayList<Registration>();
        var prices = new ArrayList<ProposedPrice>();
        var identities = new ArrayList<ProposedIdentity>();
        var reports = new ArrayList<ProductReport>();
        var seen = new HashSet<String>();
        var canonicalIds = new HashSet<String>();
        var rows = new LinkedHashMap<String, Integer>();
        for (String table : List.of("catalog_product", "catalog_reference_price", "catalog_product_source", "psu_spec",
                "psu_power_connector", "case_spec", "case_motherboard_form_factor", "case_psu_form_factor",
                "catalog_model", "catalog_model_source", "catalog_model_alias")) rows.put(table, 0);

        for (var candidate : review.products()) {
            var expected = SCOPE.get(candidate.key());
            require(expected != null && seen.add(candidate.key()), "Candidate is repeated or outside the approved seven-product scope");
            require(candidate.type() == expected.type() && expected.manufacturer().equals(candidate.manufacturer())
                    && expected.modelName().equals(candidate.modelName()) && Objects.equals(expected.partNumber(), candidate.partNumber())
                    && candidate.identityKind() == CatalogIdentityKind.PHYSICAL_VARIANT, "Exact manufacturer model, SKU and physical variant must be preserved");
            require(candidate.specification() != null && candidate.specification().isObject(), "Specification is required");
            CatalogSpecification spec = candidate.type() == PartType.PSU
                    ? MAPPER.treeToValue(candidate.specification(), CatalogSpecification.Psu.class)
                    : MAPPER.treeToValue(candidate.specification(), CatalogSpecification.Case.class);
            require(candidate.sources() != null && !candidate.sources().isEmpty()
                    && candidate.sources().stream().allMatch(Objects::nonNull), "Manufacturer evidence is required");
            var primary = candidate.sources().getFirst();
            require(primary.sourceName() == CatalogSourceName.MANUFACTURER && expected.externalId().equals(primary.externalId()),
                    "Primary manufacturer identity differs from the approved exact product");
            String canonical = CanonicalCatalogIds.productForReviewedSource(primary.sourceName(), primary.externalId());
            require(canonical.equals(candidate.canonicalId()) && canonicalIds.add(canonical), "Canonical manufacturer identity differs or is repeated");
            var sourceInputs = new ArrayList<CatalogSourceInput>();
            for (int i = 0; i < candidate.sources().size(); i++) {
                var source = candidate.sources().get(i);
                require(source.sourceName() == CatalogSourceName.MANUFACTURER && (i == 0 || source.externalId() == null),
                        "Supplemental manufacturer documents must not duplicate the external product identity");
                require(officialManufacturerUrl(expected.manufacturer(), source.sourceUrl()),
                        "Manufacturer evidence requires its official domain over HTTPS without credentials or a nonstandard port");
                timestamp(source.retrievedAt(), "source.retrievedAt");
                require(!source.retrievedAt().isAfter(review.reviewedAt()), "Evidence cannot be retrieved after its review");
                var notes = strings(source.notes(), "source.notes", true);
                var payload = new LinkedHashMap<String, Object>();
                payload.put("recordKind", "REVIEWED_MANUFACTURER_SPEC_EXTRACT");
                payload.put("reviewScope", "Approved seven-product PSU/CASE specification preview; not complete PC compatibility or price publication approval.");
                payload.put("notes", notes);
                sourceInputs.add(new CatalogSourceInput(source.sourceName(), source.externalId(), source.sourceRevision(),
                        source.sourceUrl(), payload, source.retrievedAt()));
            }
            var product = new CatalogProductCreateRequest(candidate.type(), candidate.manufacturer(), candidate.modelName(), candidate.partNumber());
            var entry = new CatalogEntryCreateRequest(product, spec, sourceInputs);
            registrations.add(new Registration(candidate.key(), canonical, candidate.identityKind(), CatalogRole.PURCHASE_CANDIDATE,
                    CatalogVerificationStatus.UNVERIFIED, false, entry));
            var manufacturerIdentity = new SourceIdentity(primary.sourceName(), primary.externalId());
            identities.add(new ProposedIdentity(canonical, product.type(), product.manufacturer(), product.modelName(),
                    product.partNumber(), null, candidate.identityKind(), CatalogRole.PURCHASE_CANDIDATE,
                    CatalogVerificationStatus.UNVERIFIED, false, manufacturerIdentity));
            increment(rows, "catalog_product", 1); increment(rows, "catalog_reference_price", 1);
            increment(rows, "catalog_product_source", sourceInputs.size());
            if (spec instanceof CatalogSpecification.Psu psu) {
                increment(rows, "psu_spec", 1); increment(rows, "psu_power_connector", psu.powerConnectors().size());
            } else if (spec instanceof CatalogSpecification.Case enclosure) {
                increment(rows, "case_spec", 1);
                increment(rows, "case_motherboard_form_factor", enclosure.motherboardFormFactors().size());
                increment(rows, "case_psu_form_factor", enclosure.psuFormFactors().size());
            }
            var priceReview = candidate.priceReview();
            require(priceReview != null && priceReview.exactVariantVerified() != null, "Explicit price review is required");
            var priceNotes = strings(priceReview.notes(), "priceReview.notes", true);
            var identityEvidence = strings(priceReview.identityEvidence(), "priceReview.identityEvidence", false);
            var priceUrls = strings(priceReview.sourceUrls(), "priceReview.sourceUrls", true);
            if (candidate.currentPrice() != null) {
                require("EXACT_VARIANT_OBSERVED".equals(priceReview.status()) && Boolean.TRUE.equals(priceReview.exactVariantVerified())
                        && "NEW".equals(priceReview.saleCondition()) && Boolean.TRUE.equals(priceReview.inStock())
                        && !identityEvidence.isEmpty(), "Only verified exact available NEW variants can propose prices");
                var observation = candidate.currentPrice();
                require(SharedPriceSourcePolicy.matches(observation.sourceName(), observation.sourceUrl(), priceReview.offerExternalId())
                        && priceUrls.contains(observation.sourceUrl()), "Price provider URL and exact offer ID must agree with the review");
                timestamp(priceReview.verifiedAt(), "priceReview.verifiedAt");
                timestamp(observation.observedAt(), "currentPrice.observedAt");
                require(!priceReview.verifiedAt().isAfter(observation.observedAt()) && !observation.observedAt().isAfter(review.reviewedAt()),
                        "Price observation must follow exact-variant review without replacing its original timestamp");
                var offer = new CatalogPriceImportBatch.Offer(observation.sourceName(), priceReview.offerExternalId(),
                        observation.sourceUrl(), priceReview.saleSku(), CatalogPriceImportBatch.SaleUnit.PRODUCT, null, priceReview.verifiedAt());
                var price = new CatalogPriceImportBatch.Price(observation.amountKrw(), observation.observedAt(), observation.sourceUrl(),
                        priceReview.saleCondition(), priceReview.inStock());
                prices.add(new ProposedPrice(candidate.key(), canonical, product, manufacturerIdentity, offer, price, identityEvidence));
            } else {
                require(priceReview.status() != null && priceReview.status().startsWith("HELD_"),
                        "Missing prices require a held decision; never invent zero or similar-variant prices");
            }
            var unknown = new ArrayList<String>();
            unknownFields(candidate.specification(), "specification", unknown);
            reports.add(new ProductReport(candidate.key(), canonical, candidate.type(), List.copyOf(unknown), conditions(spec),
                    priceReview.status(), candidate.currentPrice() != null, candidate.currentPrice() == null ? priceNotes : List.of()));
        }
        require(seen.equals(SCOPE.keySet()) && rows.get("psu_spec") == 4 && rows.get("case_spec") == 3,
                "Approved four PSU and three CASE identities must all occur once");
        var immutablePrices = List.copyOf(prices);
        var extension = new CentralExtensionProposal(1, MODE, false, false, FILE, List.copyOf(identities), immutablePrices);
        var report = new Report(1, MODE, false, false, 0, 0, 0, "V15", Map.of("PSU", 4, "CASE", 3),
                Collections.unmodifiableMap(rows), Map.of("catalog_price_mapping", prices.size(), "catalog_price_observation", prices.size()),
                prices.size(), 7 - prices.size(), List.copyOf(reports), List.of(
                        "이 결과는 등록·가격·중앙 확장 제안서다. 실제 DB 적용 또는 가격 게시 입력으로 승인되지 않았다.",
                        "기존 상품·가격·PC 연결을 읽거나 변경하지 않았다. 실제 DB와의 중복·보존 대조는 미실행이다.",
                        "V15 구조 적용·7종 채택·가격별 반영·중앙 확장 및 배포는 결과를 검토한 뒤 별도 승인해야 한다.",
                        "미확인 규격·독립 케이블 수·케이스 배치 조건은 자동 호환으로 확정하지 않는다.",
                        "기존 가격 import는 제조사 PN 없는 상품과 중앙 PSU/CASE 확장을 지원하지 않는다. 이번 가격 제안서를 그 작업에 넣지 않는다."));
        return new Plan(review, List.copyOf(registrations), immutablePrices, extension, report);
    }

    private static List<String> conditions(CatalogSpecification spec) {
        var required = new ArrayList<String>();
        if (spec instanceof CatalogSpecification.Psu psu) {
            if (!psu.powerConnectorsKnown()) required.add("메인전원·CPU·GPU 출력 플러그 목록 미확인");
            if (psu.powerConnectors().stream().anyMatch(c -> c.independentCableCount() == null)) required.add("플러그 수와 독립 케이블 수를 별도로 확인");
            if (psu.powerConnectors().stream().anyMatch(c -> c.connectorType().equals("PCIE_16PIN_UNSPECIFIED"))) required.add("일반 16핀을 12VHPWR/12V-2x6으로 추정하지 않음");
        } else if (spec instanceof CatalogSpecification.Case enclosure) {
            if (!enclosure.motherboardFormFactorsKnown()) required.add("메인보드 지원 목록 미확인");
            if (!enclosure.psuFormFactorsKnown()) required.add("파워 지원 목록 미확인");
            if (!enclosure.gpuClearanceUnconditional()) required.add(enclosure.gpuClearanceConditions() == null ? "GPU 장착 한도의 적용 조건 미확인" : enclosure.gpuClearanceConditions());
            if (!enclosure.psuClearanceUnconditional()) required.add(enclosure.psuClearanceConditions() == null ? "파워 장착 한도의 적용 조건 미확인" : enclosure.psuClearanceConditions());
        }
        return List.copyOf(required);
    }

    private static void unknownFields(JsonNode node, String path, List<String> result) {
        if (node.isNull()) { result.add(path); return; }
        if (node.isObject()) {
            for (var entry : node.properties()) unknownFields(entry.getValue(), path + "." + entry.getKey(), result);
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) unknownFields(node.get(i), path + "[" + i + "]", result);
        }
    }
    private static List<String> strings(List<String> values, String field, boolean nonempty) {
        require(values != null && (!nonempty || !values.isEmpty())
                && values.stream().allMatch(v -> v != null && !v.isBlank()), field + " requires explicit nonblank values");
        return List.copyOf(values);
    }
    private static void timestamp(Instant value, String field) {
        require(value != null && !value.isBefore(Instant.EPOCH) && !value.isAfter(Instant.now()), field + " must be a real past or present timestamp");
    }
    private static boolean officialManufacturerUrl(String manufacturer, String value) {
        if (value == null) return false;
        String domain = switch (manufacturer) {
            case "MSI" -> "msi.com";
            case "Corsair" -> "corsair.com";
            case "Fractal Design" -> "fractal-design.com";
            case "ASUS" -> "asus.com";
            case "Cooler Master" -> "coolermaster.com";
            default -> throw new IllegalArgumentException("Unsupported manufacturer in the approved scope");
        };
        try {
            var uri = URI.create(value);
            String host = uri.getHost();
            if (host != null) host = host.toLowerCase(Locale.ROOT);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getRawUserInfo() == null
                    && (uri.getPort() == -1 || uri.getPort() == 443) && host != null
                    && (host.equals(domain) || host.endsWith("." + domain));
        } catch (IllegalArgumentException ex) { return false; }
    }
    private static void increment(Map<String, Integer> rows, String table, int count) { rows.merge(table, count, Integer::sum); }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
}
