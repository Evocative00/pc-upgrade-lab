package com.pcupgradelab.catalog.seed;

import com.pcupgradelab.catalog.CatalogEntryCreateRequest;
import com.pcupgradelab.catalog.CatalogProduct;
import com.pcupgradelab.catalog.CatalogProductCreateRequest;
import com.pcupgradelab.catalog.CatalogSourceInput;
import com.pcupgradelab.catalog.CatalogSourceName;
import com.pcupgradelab.catalog.CatalogSpecification;
import com.pcupgradelab.pc.PartType;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 선택한 부품 묶음을 DB 접근 전에 모두 읽고 검증한다. */
@Component
public class CatalogSeedLoader {
    // 외부 API용 Jackson 설정과 무관하게 이 파일 형식의 검증 규칙을 고정한다.
    private final JsonMapper mapper = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .build();

    /** 기존 호출부는 계속 첫 CPU·메인보드·RAM 9종을 읽는다. */
    public List<CatalogEntryCreateRequest> load() {
        return load(CatalogSeedBatch.INITIAL);
    }

    /** DB에 쓰기 전에 선택한 묶음의 원본 파일·제원·출처 전체를 검증한다. */
    public List<CatalogEntryCreateRequest> load(CatalogSeedBatch batch) {
        if (batch == null) {
            throw new IllegalArgumentException("Catalog seed batch is required");
        }
        JsonNode manifest = readObject(readResource(resourceRoot(batch) + "manifest.json"), "manifest");
        if (!batch.resourceName().equals(requiredText(manifest, "seedName", "manifest"))) {
            throw invalid("manifest", "seedName must be " + batch.resourceName());
        }
        String revision = requiredText(manifest, "revision", "manifest");
        if (!revision.matches("[0-9a-fA-F]{40}")) {
            throw invalid("manifest", "revision must be a full 40-digit hex commit");
        }
        revision = revision.toLowerCase(Locale.ROOT);
        Instant buildcoresRetrievedAt = requiredInstant(manifest, "buildcoresRetrievedAt", "manifest");
        JsonNode items = requiredArray(manifest, "items", "manifest");
        if (items.size() != batch.expectedCount()) {
            throw invalid("manifest", "items must contain exactly " + batch.expectedCount() + " products");
        }

        var requests = new ArrayList<CatalogEntryCreateRequest>(batch.expectedCount());
        var externalIds = new HashSet<String>();
        for (int index = 0; index < items.size(); index++) {
            JsonNode item = items.get(index);
            String context = "item " + (index + 1);
            requireObject(item, context);
            String externalId = canonicalUuid(requiredText(item, "externalId", context), context);
            context = "item " + externalId;
            if (!externalIds.add(externalId)) {
                throw invalid(context, "externalId occurs more than once");
            }
            requests.add(readEntry(item, externalId, revision, buildcoresRetrievedAt, batch, context));
        }
        return List.copyOf(requests);
    }

    private CatalogEntryCreateRequest readEntry(JsonNode item, String externalId, String revision,
                                                Instant buildcoresRetrievedAt, CatalogSeedBatch batch,
                                                String context) {
        String category = requiredText(item, "category", context);
        CatalogProductCreateRequest product = readProduct(requiredObject(item, "product", context), context);
        if (!batch.supports(product.type())) {
            throw invalid(context, "product.type is not supported by " + batch.resourceName());
        }
        String expectedCategory = switch (product.type()) {
            case CPU -> "CPU";
            case MOTHERBOARD -> "Motherboard";
            case RAM -> "RAM";
            case GPU -> "GPU";
            case MONITOR -> "Monitor";
            default -> throw invalid(context, "unsupported product type");
        };
        if (!expectedCategory.equals(category)) {
            throw invalid(context, "category must match product.type");
        }
        CatalogSpecification specification = readSpecification(
                requiredObject(item, "specification", context), product.type(), context);

        String expectedHash = requiredText(item, "rawSha256", context);
        if (!expectedHash.matches("[0-9a-fA-F]{64}")) {
            throw invalid(context, "rawSha256 must be a 64-digit hex checksum");
        }
        // Windows에서 패치를 적용할 때 CRLF로 바뀔 수 있다. 원본의 LF 기준으로 검사·파싱한다.
        byte[] rawBytes = normalizeRawLineEndings(
                readResource(resourceRoot(batch) + "buildcores/" + externalId + ".json"));
        if (!expectedHash.equalsIgnoreCase(sha256(rawBytes))) {
            throw invalid(context, "BuildCores raw file SHA-256 does not match the manifest after CRLF normalization");
        }
        Map<String, Object> rawPayload = objectValue(readObject(rawBytes, context + " BuildCores raw file"));
        String rawUrl = "https://github.com/buildcores/buildcores-open-db/blob/" + revision
                + "/open-db/" + category + "/" + externalId + ".json";
        var sources = new ArrayList<CatalogSourceInput>();
        sources.add(sourceInput(CatalogSourceName.BUILDCORES, externalId, revision,
                rawUrl, rawPayload, buildcoresRetrievedAt, context));

        JsonNode manufacturerSources = requiredArray(item, "manufacturerSources", context);
        if (manufacturerSources.size() == 0) {
            throw invalid(context, "at least one manufacturer source is required");
        }
        for (int index = 0; index < manufacturerSources.size(); index++) {
            String sourceContext = context + " manufacturer source " + (index + 1);
            JsonNode source = manufacturerSources.get(index);
            requireObject(source, sourceContext);
            JsonNode payload = requiredObject(source, "payload", sourceContext);
            if (!"CURATED_SPEC_EXTRACT".equals(requiredText(payload, "record_kind", sourceContext))) {
                throw invalid(sourceContext, "payload must be labelled CURATED_SPEC_EXTRACT");
            }
            sources.add(sourceInput(CatalogSourceName.MANUFACTURER, null, null,
                    requiredText(source, "url", sourceContext), objectValue(payload),
                    requiredInstant(source, "retrievedAt", sourceContext), sourceContext));
        }
        return new CatalogEntryCreateRequest(product, specification, sources);
    }

    private CatalogProductCreateRequest readProduct(JsonNode node, String context) {
        try {
            CatalogProductCreateRequest parsed = mapper.treeToValue(node, CatalogProductCreateRequest.class);
            // record의 역직렬화만으로는 제품 문자열 검증이 끝나지 않으므로 기존 생성자를 재사용한다.
            var checked = new CatalogProduct(parsed.type(), parsed.manufacturer(), parsed.modelName(), parsed.partNumber());
            return new CatalogProductCreateRequest(checked.getType(), checked.getManufacturer(),
                    checked.getModelName(), checked.getPartNumber());
        } catch (RuntimeException ex) {
            throw new IllegalStateException("Invalid catalog seed " + context + ": product fields are invalid", ex);
        }
    }

    private CatalogSpecification readSpecification(JsonNode node, PartType type, String context) {
        try {
            return switch (type) {
                case CPU -> mapper.treeToValue(node, CatalogSpecification.Cpu.class);
                case MOTHERBOARD -> mapper.treeToValue(node, CatalogSpecification.Motherboard.class);
                case RAM -> mapper.treeToValue(node, CatalogSpecification.Ram.class);
                case GPU -> mapper.treeToValue(node, CatalogSpecification.Gpu.class);
                case MONITOR -> mapper.treeToValue(node, CatalogSpecification.Monitor.class);
                default -> throw invalid(context, "unsupported specification type");
            };
        } catch (RuntimeException ex) {
            throw new IllegalStateException("Invalid catalog seed " + context + ": specification fields are invalid", ex);
        }
    }

    private CatalogSourceInput sourceInput(CatalogSourceName name, String externalId, String revision,
                                           String url, Map<String, Object> payload, Instant retrievedAt,
                                           String context) {
        try {
            return new CatalogSourceInput(name, externalId, revision, url, payload, retrievedAt);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Invalid catalog seed " + context + ": source fields are invalid", ex);
        }
    }

    private byte[] readResource(String path) {
        try (var stream = new ClassPathResource(path).getInputStream()) {
            return stream.readAllBytes();
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot read catalog seed resource: " + path, ex);
        }
    }

    private static String resourceRoot(CatalogSeedBatch batch) {
        return "catalog/seed/" + batch.resourceName() + "/";
    }

    private JsonNode readObject(byte[] bytes, String context) {
        final JsonNode node;
        try {
            node = mapper.readTree(bytes);
        } catch (RuntimeException ex) {
            throw new IllegalStateException("Invalid catalog seed " + context + ": malformed JSON", ex);
        }
        requireObject(node, context);
        return node;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> objectValue(JsonNode object) {
        // 호출부에서 객체임을 확인했다. JSON의 null·배열·큰 정수·소수를 보존한다.
        return (Map<String, Object>) mapper.treeToValue(object, Map.class);
    }

    private static JsonNode requiredObject(JsonNode parent, String field, String context) {
        JsonNode node = parent.get(field);
        requireObject(node, context + "." + field);
        return node;
    }

    private static void requireObject(JsonNode node, String context) {
        if (node == null || !node.isObject()) {
            throw invalid(context, "a JSON object is required");
        }
    }

    private static JsonNode requiredArray(JsonNode parent, String field, String context) {
        JsonNode node = parent.get(field);
        if (node == null || !node.isArray()) {
            throw invalid(context, field + " must be an array");
        }
        return node;
    }

    private static String requiredText(JsonNode parent, String field, String context) {
        JsonNode node = parent.get(field);
        if (node == null || !node.isString() || node.stringValue().isBlank()) {
            throw invalid(context, field + " must be a non-blank string");
        }
        return node.stringValue().strip();
    }

    private static Instant requiredInstant(JsonNode parent, String field, String context) {
        try {
            return Instant.parse(requiredText(parent, field, context));
        } catch (DateTimeParseException ex) {
            throw new IllegalStateException("Invalid catalog seed " + context + ": " + field + " must be an ISO instant", ex);
        }
    }

    private static String canonicalUuid(String value, String context) {
        try {
            String canonical = UUID.fromString(value).toString();
            if (!canonical.equalsIgnoreCase(value)) {
                throw invalid(context, "externalId must be a canonical UUID");
            }
            return canonical;
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Invalid catalog seed " + context + ": externalId must be a canonical UUID", ex);
        }
    }

    /** CRLF의 CR만 제거한다. 단독 CR·BOM·공백·문자열 안의 이스케이프 등 다른 바이트는 보존한다. */
    static byte[] normalizeRawLineEndings(byte[] bytes) {
        var normalized = new ByteArrayOutputStream(bytes.length);
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == '\r' && i + 1 < bytes.length && bytes[i + 1] == '\n') continue;
            normalized.write(bytes[i]);
        }
        return normalized.toByteArray();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private static IllegalStateException invalid(String context, String message) {
        return new IllegalStateException("Invalid catalog seed " + context + ": " + message);
    }
}
