package com.pcupgradelab.catalog.price;

import com.pcupgradelab.catalog.CatalogSourceName;
import com.pcupgradelab.catalog.CatalogSpecification;
import com.pcupgradelab.catalog.seed.CatalogSeedBatch;
import com.pcupgradelab.catalog.seed.CatalogSeedLoader;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 배포 리소스·검토한 제품 연결·기본 실행 옵션을 검사하며 DB나 외부 사이트를 사용하지 않는다. */
class CatalogPriceSnapshotTests {
    private final CatalogPriceSnapshotLoader snapshots = new CatalogPriceSnapshotLoader(new CatalogPriceImportLoader());

    @Test
    void bundlesExactlyThe72ReviewedProductsAndKeepsOriginalPricesAndRamUnits() {
        var snapshot = snapshots.load();
        assertThat(snapshot.schemaVersion()).isEqualTo(1);
        assertThat(snapshot.items()).hasSize(72);
        assertThat(snapshot.items()).extracting(item -> item.product().externalId()).doesNotHaveDuplicates();
        assertThat(snapshot.items()).extracting(item -> item.offer().sourceName() + "/" + item.offer().externalId())
                .doesNotHaveDuplicates();
        var sample = snapshot.items().stream().filter(item -> item.product().modelName().equals("Core i5-12400F"))
                .findFirst().orElseThrow();
        assertThat(sample.product().externalId()).isEqualTo("19fbd8f2-a226-4b83-9e5d-683db638d337");
        assertThat(sample.product().partNumber()).isEqualTo("BX8071512400F");
        assertThat(sample.price().amountKrw()).isEqualTo(215990L);
        assertThat(sample.price().observedAt()).hasToString("2026-10-06T12:39:06.581Z");
        assertThat(snapshot.items().stream().filter(item -> item.offer().saleUnit() == CatalogPriceImportBatch.SaleUnit.RAM_KIT))
                .hasSize(2).allSatisfy(item -> assertThat(item.offer().moduleCount()).isEqualTo(2));
    }

    @Test
    void everyBundledPriceResolvesToTheExistingReviewedCatalogWithItsExactSaleUnit() {
        var catalog = new HashMap<String, com.pcupgradelab.catalog.CatalogEntryCreateRequest>();
        var seeds = new CatalogSeedLoader();
        for (var batch : CatalogSeedBatch.values()) {
            for (var entry : seeds.load(batch)) {
                var id = entry.sources().stream().filter(source -> source.sourceName() == CatalogSourceName.BUILDCORES)
                        .findFirst().orElseThrow().externalId();
                assertThat(catalog.putIfAbsent(id, entry)).isNull();
            }
        }
        assertThat(catalog).hasSize(300);
        var priceCounts = new HashMap<String, Integer>();
        for (var item : snapshots.load().items()) {
            var entry = catalog.get(item.product().externalId());
            assertThat(entry).as(item.product().modelName()).isNotNull();
            assertThat(entry.product().manufacturer()).isEqualTo(item.product().manufacturer());
            assertThat(entry.product().modelName()).isEqualTo(item.product().modelName());
            if (item.product().partNumber() != null) assertThat(entry.product().partNumber()).isEqualTo(item.product().partNumber());
            priceCounts.merge(entry.product().type().name(), 1, Integer::sum);
            if (entry.specification() instanceof CatalogSpecification.Ram ram) {
                assertThat(item.offer().saleUnit()).isEqualTo(CatalogPriceImportBatch.SaleUnit.RAM_KIT);
                assertThat(item.offer().moduleCount()).isEqualTo(ram.moduleCount());
            } else {
                assertThat(item.offer().saleUnit()).isEqualTo(CatalogPriceImportBatch.SaleUnit.PRODUCT);
            }
        }
        assertThat(priceCounts).isEqualTo(Map.of("CPU", 33, "MOTHERBOARD", 21, "GPU", 12, "RAM", 2, "MONITOR", 4));
    }

    @Test
    void readsTheSameSnapshotFromJarResourcesWithoutFilesystemPaths(@TempDir Path directory) throws Exception {
        Path archive = directory.resolve("price-resources.jar");
        try (var jar = new JarOutputStream(Files.newOutputStream(archive))) {
            for (String resource : CatalogPriceSnapshotLoader.RESOURCES) {
                jar.putNextEntry(new JarEntry(resource));
                try (var input = new ClassPathResource(resource).getInputStream()) { input.transferTo(jar); }
                jar.closeEntry();
            }
        }
        try (var classLoader = new URLClassLoader(new URL[]{archive.toUri().toURL()}, null)) {
            assertThat(classLoader.getResource(CatalogPriceSnapshotLoader.RESOURCES.getFirst()).getProtocol()).isEqualTo("jar");
            assertThat(snapshots.load(classLoader)).isEqualTo(snapshots.load());
        }
    }

    @Test
    void refusesAnIncompleteDistributionInsteadOfSilentlyImportingOnlyTheFirstBatch(@TempDir Path directory) throws Exception {
        Path firstResource = directory.resolve(CatalogPriceSnapshotLoader.RESOURCES.getFirst());
        Files.createDirectories(firstResource.getParent());
        try (var input = new ClassPathResource(CatalogPriceSnapshotLoader.RESOURCES.getFirst()).getInputStream()) {
            Files.copy(input, firstResource);
        }
        try (var classLoader = new URLClassLoader(new URL[]{directory.toUri().toURL()}, null)) {
            assertThatThrownBy(() -> snapshots.load(classLoader)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Unable to read bundled price snapshot");
        }
    }

    @Test
    void defaultsToPreviewAndRequiresTheExplicitApplySwitchForEitherOperation() {
        assertThat(CatalogPriceImportApplication.Options.parse(new String[0]))
                .isEqualTo(new CatalogPriceImportApplication.Options(false, false));
        assertThat(CatalogPriceImportApplication.Options.parse(new String[]{"--setup-catalog"}))
                .isEqualTo(new CatalogPriceImportApplication.Options(false, true));
        assertThat(CatalogPriceImportApplication.Options.parse(new String[]{"--apply"}))
                .isEqualTo(new CatalogPriceImportApplication.Options(true, false));
        assertThat(CatalogPriceImportApplication.Options.parse(new String[]{"--setup-catalog", "--apply"}))
                .isEqualTo(new CatalogPriceImportApplication.Options(true, true));
    }

    @Test
    void refusesUnknownArgumentsBeforeAnApplicationContextCanStart() {
        for (String argument : new String[]{"--apply=true", "--catalog.seed.enabled=true", "--spring.flyway.enabled=true"}) {
            assertThatThrownBy(() -> CatalogPriceImportApplication.Options.parse(new String[]{argument}))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unsupported import argument");
        }
    }
}
