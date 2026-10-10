package com.pcupgradelab.catalog.shared;

import com.pcupgradelab.catalog.identity.CatalogIdentityKind;
import com.pcupgradelab.catalog.identity.CatalogRole;
import com.pcupgradelab.catalog.pilot.CatalogPilotImportLoader;
import com.pcupgradelab.catalog.price.CatalogPriceImportLoader;
import com.pcupgradelab.catalog.price.CatalogPriceSnapshotLoader;
import com.pcupgradelab.pc.PartType;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AllCatalogManifestTests {
    private final Path root = Path.of("..").toAbsolutePath().normalize();

    @Test void merges307StableIdentitiesWith74OriginalObservationsAndPreservesThePilot() {
        var plan = new AllCatalogManifestGenerator().load(root);
        var pilot = new CatalogPilotImportLoader().load(root);
        assertThat(plan.manifest().products()).hasSize(307);
        assertThat(plan.products().stream().filter(p -> p.bindLegacy())).hasSize(293);
        assertThat(plan.prices().items()).hasSize(74);
        assertThat(plan.manifest().products().stream().map(p -> p.identity().canonicalId())).doesNotHaveDuplicates().isSorted();
        for (var part : pilot.parts()) {
            var product = plan.products().stream().filter(p -> p.identity().canonicalId().equals(part.proposedCanonicalId())).findFirst().orElseThrow();
            assertThat(product.bindLegacy()).isFalse();
            assertThat(product.identity().identityKind()).isEqualTo(part.identityKind());
            assertThat(product.identity().role()).isEqualTo(part.role());
            assertThat(product.specification()).isEqualTo(part.specification());
        }
        assertThat(plan.prices().items()).containsAll(pilot.prices().items());
        var original = new CatalogPriceSnapshotLoader(new CatalogPriceImportLoader()).load();
        var pilotSources = pilot.prices().items().stream().map(AllCatalogManifestGenerator::source).toList();
        var unchangedPrices = original.items().stream().filter(p -> !pilotSources.contains(AllCatalogManifestGenerator.source(p))).toList();
        assertThat(unchangedPrices).hasSize(66);
        assertThat(plan.prices().items()).containsAll(unchangedPrices);
        for (var product : plan.products().stream().filter(p -> p.bindLegacy()).toList()) {
            assertThat(product.identity().identityKind()).isEqualTo(CatalogIdentityKind.LEGACY_UNCLASSIFIED);
            assertThat(product.identity().role()).isEqualTo(CatalogRole.UNASSIGNED);
            assertThat(product.identity().active()).isFalse();
        }
    }

    @Test void singleMemoryModulesAndCompleteRetailKitsHaveDifferentSalesUnits() {
        var plan = new AllCatalogManifestGenerator().load(root);
        var ram = plan.manifest().products().stream().map(p -> p.identity()).filter(i -> i.type() == PartType.RAM).toList();
        assertThat(ram).hasSize(60);
        assertThat(ram.stream().filter(i -> i.moduleCount() == 1)).hasSize(26).allMatch(i -> i.saleUnit().equals("PRODUCT"));
        assertThat(ram.stream().filter(i -> i.moduleCount() > 1)).hasSize(34).allMatch(i -> i.saleUnit().equals("RAM_KIT"));
        assertThat(plan.prices().items().stream().filter(i -> i.offer().saleUnit().name().equals("RAM_KIT")))
                .hasSize(2).allMatch(i -> i.offer().moduleCount() == 2);
    }

    @Test void commandDefaultsToDbFreeReviewAndRejectsAmbiguousApplyOptions() {
        var options = AllCatalogIdentityImportApplication.Options.parse(new String[]{"--source-root",root.toString()});
        assertThat(options.apply()).isFalse(); assertThat(options.checkDb()).isFalse();
        assertThatThrownBy(() -> AllCatalogIdentityImportApplication.Options.parse(new String[]{"--source-root",root.toString(),"--apply","--check-db"}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AllCatalogIdentityImportApplication.Options.parse(new String[]{"--apply"}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AllCatalogIdentityImportApplication.Options.parse(new String[]{"--source-root",root.toString(),"--apply","--apply"}))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
