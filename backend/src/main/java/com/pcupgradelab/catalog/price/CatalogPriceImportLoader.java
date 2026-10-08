package com.pcupgradelab.catalog.price;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

/** 알 수 없는 필드, 중복 JSON 키, 소수 금액, 뒤에 붙은 JSON을 거절한다. */
@Component
public class CatalogPriceImportLoader {
    private static final int MAX_BYTES = 5 * 1024 * 1024;
    private final JsonMapper mapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .build();

    public CatalogPriceImportBatch load(Path path) {
        if (path == null) throw new IllegalArgumentException("Price import file path is required");
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("Price import file exceeds 5 MiB");
            return read(bytes);
        } catch (IOException ex) {
            throw new IllegalArgumentException("Unable to read price import file", ex);
        }
    }

    public CatalogPriceImportBatch read(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) {
            throw new IllegalArgumentException("Price import JSON must contain 1 byte..5 MiB");
        }
        try {
            var tree = mapper.readTree(bytes);
            if (tree == null || !tree.isObject()) throw new IllegalArgumentException("Price import JSON must be an object");
            return mapper.treeToValue(tree, CatalogPriceImportBatch.class);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("Invalid price import JSON", ex);
        }
    }
}
