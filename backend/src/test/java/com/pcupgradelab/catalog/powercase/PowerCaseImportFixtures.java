package com.pcupgradelab.catalog.powercase;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

final class PowerCaseImportFixtures {
    private PowerCaseImportFixtures() { }
    static Path copyPublicInputs(Path target) throws Exception {
        Path source = Path.of("..").toAbsolutePath().normalize();
        var review = List.of("power-case-pilot-2026-10-11.json", "retail-four-preview-2026-10-10.json",
                "retail-asus-apply-approval-2026-10-10.json", "pilot-import-preview-2026-10-10.json",
                "pilot-approved-prices-2026-10-10.json", "pilot-apply-approval-2026-10-10.json",
                "pilot-selection-2026-10-10.json", "catalog-selection-2026-10-09.json",
                "pilot-platform-evidence-2026-10-10.json", "pilot-ram-gpu-evidence-2026-10-10.json",
                "pilot-storage-evidence-2026-10-10.json", "pilot-existing-prices-2026-10-10.json",
                "pilot-existing-prices-2026-10-10.json.report.json", "pilot-existing-price-assessment-2026-10-10.json",
                "pilot-model-price-review-2026-10-10.json", "pilot-storage-price-review-2026-10-10.json");
        for (String file : review) copy(source,target,"data/catalog-review/" + file);
        for (String file : List.of("observations-2026-10-06.json","observations-expansion50-2026-10-08.json"))
            copy(source,target,"data/catalog-current-prices/" + file);
        return target;
    }
    static Path writeApproval(Path root,PowerCaseCatalogImportLoader.Plan plan,boolean approved) throws Exception {
        var mapper = JsonMapper.builder().build();
        var tree = (ObjectNode) mapper.valueToTree(new PowerCaseCatalogImportLoader().approvalProposal(plan));
        if (approved) tree.put("decision","APPROVED").put("databaseApplyApproved",true);
        Path path = root.resolve("database-approval-test.json");
        Files.writeString(path,mapper.writeValueAsString(tree),StandardCharsets.UTF_8);
        return path;
    }
    private static void copy(Path source,Path target,String relative) throws Exception {
        Files.createDirectories(target.resolve(relative).getParent());
        Files.copy(source.resolve(relative),target.resolve(relative));
    }
}
