package com.pcupgradelab.catalog.shared;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class RetailCatalogApplyLoaderTests {
    @TempDir Path temporary;
    private final Path root = Path.of("..").toAbsolutePath().normalize();
    @Test void exactApprovalLoadsOneReadySaleWithoutTurningPreviewFlagsIntoDbAuthorization() {
        var plan = new RetailCatalogApplyLoader().load(root);
        assertThat(plan.sale().identity().identity().canonicalId()).isEqualTo(RetailCatalogApplyLoader.PRODUCT_CANONICAL);
        assertThat(plan.sale().modelCanonicalId()).isEqualTo(RetailCatalogApplyLoader.MODEL_CANONICAL);
        assertThat(plan.baseline().products()).hasSize(307);
        assertThat(plan.priceBatch().items()).hasSize(1);
        assertThat(plan.priceBatch().items().getFirst().price().amountKrw()).isEqualTo(275350);
        assertThat(plan.previewSha256()).matches("[a-f0-9]{64}");
    }
    @Test void rawPreviewByteChangesOrMissingSeparateApprovalStopBeforeDbAccess() throws Exception {
        copyApproval();
        assertThatThrownBy(() -> new RetailCatalogApplyLoader().load(temporary)).isInstanceOf(IllegalArgumentException.class);
        var preview = temporary.resolve(RetailCatalogPreviewLoader.FILE);
        Files.createDirectories(preview.getParent());
        Files.writeString(preview,Files.readString(root.resolve(RetailCatalogPreviewLoader.FILE)) + "\n");
        assertThatThrownBy(() -> new RetailCatalogApplyLoader().load(temporary)).hasMessageContaining("SHA-256 differs");
    }
    @Test void expandedScopeUnknownFieldsAndDuplicateApprovalKeysAreRejected() throws Exception {
        copyApproval();
        String approval = Files.readString(root.resolve(RetailCatalogApplyLoader.APPROVAL_FILE));
        var target = temporary.resolve(RetailCatalogApplyLoader.APPROVAL_FILE);
        Files.writeString(target,approval.replace("\"newProducts\": 1", "\"newProducts\": 2"));
        assertThatThrownBy(() -> new RetailCatalogApplyLoader().load(temporary)).hasMessageContaining("one-product approval");
        Files.writeString(target,approval.replaceFirst("\\{", "{\"unexpected\":true,"));
        assertThatThrownBy(() -> new RetailCatalogApplyLoader().load(temporary)).isInstanceOf(IllegalArgumentException.class);
        Files.writeString(target,approval.replace("\"decision\": \"APPROVED\"", "\"decision\":\"DENIED\",\"decision\":\"APPROVED\""));
        assertThatThrownBy(() -> new RetailCatalogApplyLoader().load(temporary)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void cliDefaultsToDbFreeAndRequiresExplicitMutuallyExclusiveDbMode() {
        var options = RetailCatalogApplyApplication.Options.parse(new String[]{"--source-root",root.toString()});
        assertThat(options.apply()).isFalse(); assertThat(options.checkDb()).isFalse();
        assertThat(RetailCatalogApplyApplication.Options.parse(new String[]{"--source-root",root.toString(),"--check-db"}).checkDb()).isTrue();
        assertThat(RetailCatalogApplyApplication.Options.parse(new String[]{"--source-root",root.toString(),"--apply"}).apply()).isTrue();
        for (String[] invalid : new String[][]{{}, {"--apply"}, {"--source-root",root.toString(),"--apply","--check-db"},
                {"--source-root",root.toString(),"--check-db","--check-db"}, {"--source-root",root.toString(),"--force"}})
            assertThatThrownBy(() -> RetailCatalogApplyApplication.Options.parse(invalid)).isInstanceOf(IllegalArgumentException.class);
    }
    private void copyApproval() throws Exception {
        var target = temporary.resolve(RetailCatalogApplyLoader.APPROVAL_FILE);
        Files.createDirectories(target.getParent()); Files.copy(root.resolve(RetailCatalogApplyLoader.APPROVAL_FILE),target);
    }
}
