package com.pcupgradelab.catalog.price;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/** 팀원의 각 DB에 같은 검토된 관측을 적용한다. 파일과 실행 JAR에서 모두 읽을 수 있다. */
@Component
public class CatalogPriceSnapshotLoader {
    static final List<String> RESOURCES = List.of(
            "catalog/prices/observations-2026-10-06.json",
            "catalog/prices/observations-expansion50-2026-10-08.json");
    private final CatalogPriceImportLoader loader;

    public CatalogPriceSnapshotLoader(CatalogPriceImportLoader loader) {
        this.loader = loader;
    }

    public CatalogPriceImportBatch load() {
        return load(CatalogPriceSnapshotLoader.class.getClassLoader());
    }

    CatalogPriceImportBatch load(ClassLoader classLoader) {
        var items = new ArrayList<CatalogPriceImportBatch.Item>();
        for (String name : RESOURCES) {
            try (var input = new ClassPathResource(name, classLoader).getInputStream()) {
                items.addAll(loader.read(input.readNBytes(5 * 1024 * 1024 + 1)).items());
            } catch (IOException ex) {
                throw new IllegalStateException("Unable to read bundled price snapshot: " + name, ex);
            }
        }
        if (items.size() != 72) throw new IllegalStateException("The reviewed price snapshot must contain 72 items");
        return new CatalogPriceImportBatch(1, items);
    }
}
