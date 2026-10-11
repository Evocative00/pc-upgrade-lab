package com.pcupgradelab.catalog.powercase;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

class PowerCaseCatalogImportLoaderTests {
    @TempDir Path directory;
    @Test void pendingSourceNeedsSeparateExactApprovalAndNormalizedHashSurvivesGitLineEndingChanges() throws Exception {
        PowerCaseImportFixtures.copyPublicInputs(directory);
        var loader = new PowerCaseCatalogImportLoader();
        var plan = loader.loadForCheck(directory);
        assertThat(plan.databaseApplyApproved()).isFalse();
        assertThat(plan.baseline().baseline().products()).hasSize(307);
        assertThat(plan.previewSha256()).isEqualTo("e3575f9217ea88797ceec8788adf8f86e1440f2521c672e41a4ba42fc17e5830");
        var pending = PowerCaseImportFixtures.writeApproval(directory,plan,false);
        assertThatThrownBy(() -> loader.loadApproved(directory,pending)).hasMessageContaining("Exact separate database approval");
        Path approval = PowerCaseImportFixtures.writeApproval(directory,plan,true);
        assertThat(loader.loadApproved(directory,approval).databaseApplyApproved()).isTrue();
        Path source = directory.resolve(PowerCaseCatalogPreviewLoader.FILE);
        String original = Files.readString(source,StandardCharsets.UTF_8);
        if (original.startsWith("\uFEFF")) original=original.substring(1);
        Files.writeString(source,"\uFEFF" + original.replace("\r\n","\n").replace("\n","\r\n"),StandardCharsets.UTF_8);
        assertThat(loader.loadApproved(directory,approval).previewSha256()).isEqualTo(plan.previewSha256());
        var mapper = JsonMapper.builder().build();
        String exactApproval = Files.readString(approval);
        for (String field : new String[]{"previewSha256","canonicalIds","priceObservations","publicationApproved","autoActivation"}) {
            var tree = (ObjectNode) mapper.readTree(exactApproval);
            switch (field) {
                case "previewSha256" -> tree.put(field,"0".repeat(64));
                case "canonicalIds", "priceObservations" -> tree.putArray(field);
                default -> tree.put(field,true);
            }
            Files.writeString(approval,mapper.writeValueAsString(tree));
            assertThatThrownBy(() -> loader.loadApproved(directory,approval)).as(field).hasMessageContaining("Exact separate database approval");
        }
        Files.writeString(approval,exactApproval);
        Files.writeString(source,original + " ",StandardCharsets.UTF_8);
        assertThatThrownBy(() -> loader.loadApproved(directory,approval)).hasMessageContaining("Exact separate database approval");
    }
    @Test void cliRejectsImplicitApplicationConflictingModesAndApprovalOnPreviewBeforeSpringStartup() {
        assertThat(PowerCaseCatalogImportApplication.Options.parse(new String[]{"--source-root",directory.toString()}).apply()).isFalse();
        for (String[] args : new String[][]{
                {"--source-root",directory.toString(),"--apply"},
                {"--source-root",directory.toString(),"--approval-file","x.json"},
                {"--source-root",directory.toString(),"--apply","--check-db","--approval-file","x.json"},
                {"--source-root",directory.toString(),"--apply=true"},
                {"--source-root",directory.toString(),"--check-db","--check-db"}})
            assertThatThrownBy(() -> PowerCaseCatalogImportApplication.Options.parse(args)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void defaultImportWritesOnlyPendingProposalsWithoutPrivateConfigurationOrActiveManifest() throws Exception {
        PowerCaseImportFixtures.copyPublicInputs(directory);
        for (String path : new String[]{"backend/src/main/resources/application-local.properties","data/catalog-shared/active-approved-prices.json"}) {
            Files.createDirectories(directory.resolve(path).getParent()); Files.writeString(directory.resolve(path),"sentinel - never parse");
        }
        PowerCaseCatalogImportApplication.main(new String[]{"--source-root",directory.toString()});
        var proposal = JsonMapper.builder().build().readTree(Files.readString(directory.resolve(PowerCaseCatalogImportApplication.OUTPUT)
                .resolve("database-approval-proposed.json")));
        assertThat(proposal.get("decision").asText()).isEqualTo("PENDING");
        assertThat(proposal.get("databaseApplyApproved").asBoolean()).isFalse();
        assertThat(proposal.get("publicationApproved").asBoolean()).isFalse();
        assertThat(proposal.get("canonicalIds").size()).isEqualTo(7);
        assertThat(Files.readString(directory.resolve("backend/src/main/resources/application-local.properties"))).isEqualTo("sentinel - never parse");
        assertThat(Files.readString(directory.resolve("data/catalog-shared/active-approved-prices.json"))).isEqualTo("sentinel - never parse");
        assertThatThrownBy(() -> PowerCaseCatalogImportApplication.main(new String[]{"--source-root",directory.toString(),"--apply",
                "--approval-file",directory.resolve(PowerCaseCatalogImportApplication.OUTPUT).resolve("database-approval-proposed.json").toString()}))
                .hasMessageContaining("Exact separate database approval");
    }
}
