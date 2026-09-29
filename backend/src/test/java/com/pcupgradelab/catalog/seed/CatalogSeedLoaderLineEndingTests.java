package com.pcupgradelab.catalog.seed;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 운영체제의 줄바꿈 변환만 허용하며 실제 원본 변경 검사는 유지한다. DB가 필요 없는 회귀 검사다. */
class CatalogSeedLoaderLineEndingTests {
    @Test
    void everyBatchMatchesTheManifestWithLfOrCrlfLineEndings() throws IOException {
        for (CatalogSeedBatch batch : CatalogSeedBatch.values()) {
            var manifest = JsonMapper.builder().build().readTree(resource(batch, "manifest.json"));
            var items = manifest.get("items");
            assertThat(items.size()).isEqualTo(batch.expectedCount());
            for (int index = 0; index < items.size(); index++) {
                var item = items.get(index);
                String id = item.get("externalId").stringValue();
                String expectedHash = item.get("rawSha256").stringValue();
                String lf = new String(resource(batch, "buildcores/" + id + ".json"), StandardCharsets.UTF_8)
                        .replace("\r\n", "\n");
                byte[] lfBytes = bytes(lf);
                byte[] crlfBytes = bytes(lf.replace("\n", "\r\n"));

                assertThat(hash(lfBytes)).as("Original manifest checksum: %s", id).isEqualTo(expectedHash);
                assertThat(hash(crlfBytes)).as("Regression: raw Windows bytes differ: %s", id)
                        .isNotEqualTo(expectedHash);
                for (byte[] input : List.of(lfBytes, crlfBytes)) {
                    byte[] before = input.clone();
                    byte[] normalized = CatalogSeedLoader.normalizeRawLineEndings(input);
                    assertThat(normalized).as("Normalized source: %s", id).isEqualTo(lfBytes);
                    assertThat(hash(normalized)).isEqualTo(expectedHash);
                    assertThat(input).as("Caller bytes must remain unchanged: %s", id).isEqualTo(before);
                }
            }
        }
    }

    @Test
    void mixedLineEndingsPreserveUtf8TextAndJsonEscapes() {
        byte[] input = bytes("{\r\n  \"name\": \"메모리\",\n  \"escaped\": \"\\r\\n\"\r\n}\n");
        byte[] expected = bytes("{\n  \"name\": \"메모리\",\n  \"escaped\": \"\\r\\n\"\n}\n");
        assertThat(CatalogSeedLoader.normalizeRawLineEndings(input)).isEqualTo(expected);
    }

    @Test
    void changedNumbersExtraWhitespaceBomAndLoneCarriageReturnsStillChangeTheHash() {
        String original = "{\n  \"capacity\": 16\n}\n";
        String expectedHash = hash(bytes(original));
        for (String changed : List.of(
                original.replace("16", "32"),
                original.replace("16", "16 "),
                "\uFEFF" + original,
                original.replace("\n", "\r"),
                original.replaceFirst("\n", "\r\r\n"))) {
            byte[] normalized = CatalogSeedLoader.normalizeRawLineEndings(bytes(changed));
            assertThat(hash(normalized)).isNotEqualTo(expectedHash);
        }
        byte[] trailingCr = bytes(original + "\r");
        assertThat(CatalogSeedLoader.normalizeRawLineEndings(trailingCr)).isEqualTo(trailingCr);
    }

    private static byte[] resource(CatalogSeedBatch batch, String path) throws IOException {
        try (var stream = new ClassPathResource("catalog/seed/" + batch.resourceName() + "/" + path).getInputStream()) {
            return stream.readAllBytes();
        }
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }

    private static String hash(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
