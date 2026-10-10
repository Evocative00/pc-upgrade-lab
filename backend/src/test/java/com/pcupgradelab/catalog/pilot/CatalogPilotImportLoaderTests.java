package com.pcupgradelab.catalog.pilot;

import com.pcupgradelab.catalog.CatalogSourceName;
import com.pcupgradelab.catalog.CatalogSpecification;
import com.pcupgradelab.catalog.identity.CatalogIdentityKind;
import com.pcupgradelab.catalog.identity.CatalogRole;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Typed input and approval boundaries are checked using public files without Spring or database access. */
class CatalogPilotImportLoaderTests {
    private final CatalogPilotImportLoader loader = new CatalogPilotImportLoader();
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final Path repositoryRoot = Path.of("..").toAbsolutePath().normalize();

    @Test
    void convertsFrozenApprovalToTypedPlanAndRetainsUnknowns() {
        var plan = loader.load(repositoryRoot);
        assertThat(plan.models()).hasSize(13);
        assertThat(plan.parts()).hasSize(14);
        assertThat(plan.cpuSupportPairs()).hasSize(8);
        assertThat(plan.prices().items()).hasSize(8);
        assertThat(plan.parts().get(3).product().partNumber()).isNull();
        assertThat(plan.parts().get(3).specification()).isInstanceOf(CatalogSpecification.Cpu.class);
        assertThat(plan.parts().get(4).identityKind()).isEqualTo(CatalogIdentityKind.MODEL_REFERENCE);
        assertThat(plan.parts().get(4).role()).isEqualTo(CatalogRole.INSTALLED_PC_REFERENCE);
        assertThat(plan.parts().get(4).storageSupport().hardwareRevision()).isNull();
        assertThat(plan.parts().get(12).product().partNumber()).isEqualTo("MZ-77E1T0BW");
        assertThat(plan.parts().get(13).specification()).isInstanceOf(CatalogSpecification.Storage.class);
        assertThat(plan.prices().items().stream().filter(item -> item.product().sourceName() == CatalogSourceName.MANUFACTURER)).hasSize(2);
        assertThat(plan.cpuSupportPairs().stream().filter(pair -> pair.boardSelectionNumber() == 7)
                .allMatch(pair -> pair.seedEntryJson().path("cpuExternalId").isNull()
                        && "UNVERIFIED".equals(pair.seedEntryJson().path("supportStatus").stringValue())
                        && pair.seedEntryJson().path("minimumBiosVersion").isNull())).isTrue();
    }

    @Test
    void rejectsPriceChangesAfterConcreteApproval(@TempDir Path directory) throws Exception {
        copyReview(directory);
        Path prices = directory.resolve(CatalogPilotImportLoader.PRICES_FILE);
        ObjectNode tree = (ObjectNode) mapper.readTree(Files.readAllBytes(prices));
        ((ObjectNode) tree.path("items").get(0).path("price")).put("amountKrw", 1L);
        Files.write(prices, mapper.writeValueAsBytes(tree));
        assertThatThrownBy(() -> loader.load(directory)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("hash mismatch");
    }

    @Test
    void rejectsApprovalActivationPromotionAndMissingScope(@TempDir Path directory) throws Exception {
        copyReview(directory);
        Path approval = directory.resolve(CatalogPilotImportLoader.APPROVAL_FILE);
        ObjectNode tree = (ObjectNode) mapper.readTree(Files.readAllBytes(approval));
        ((ObjectNode) tree.path("scope")).put("autoActivation", true);
        Files.write(approval, mapper.writeValueAsBytes(tree));
        assertThatThrownBy(() -> loader.load(directory)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("activation scope");
        ((ObjectNode) tree.path("scope")).put("autoActivation", false);
        ((ObjectNode) tree.path("scope")).remove("githubPublish");
        Files.write(approval, mapper.writeValueAsBytes(tree));
        assertThatThrownBy(() -> loader.load(directory)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsPrivateOrOtherApprovalTargets(@TempDir Path directory) throws Exception {
        copyReview(directory);
        Path approval = directory.resolve(CatalogPilotImportLoader.APPROVAL_FILE);
        ObjectNode tree = (ObjectNode) mapper.readTree(Files.readAllBytes(approval));
        tree.put("pricesFile", "backend/src/main/resources/application-local.properties");
        Files.write(approval, mapper.writeValueAsBytes(tree));
        assertThatThrownBy(() -> loader.load(directory)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exact public pilot files");
    }

    private void copyReview(Path destination) throws Exception {
        ObjectNode preview = (ObjectNode) mapper.readTree(Files.readAllBytes(repositoryRoot.resolve(CatalogPilotPreviewLoader.PREVIEW_FILE)));
        for (var source : preview.path("sourceHashes")) copy(destination, source.path("file").stringValue());
        copy(destination, CatalogPilotPreviewLoader.PREVIEW_FILE);
        copy(destination, CatalogPilotImportLoader.PRICES_FILE);
        copy(destination, CatalogPilotImportLoader.APPROVAL_FILE);
    }

    private void copy(Path destination, String relative) throws Exception {
        Path target = destination.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.copy(repositoryRoot.resolve(relative), target);
    }
}
