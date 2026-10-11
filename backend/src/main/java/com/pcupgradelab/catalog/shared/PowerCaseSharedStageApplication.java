package com.pcupgradelab.catalog.shared;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/** Pure file export of unpublished staging. No database, Spring, HTTP server, credentials or deployment. */
public final class PowerCaseSharedStageApplication {
    public static final String OUTPUT = "backend/build/catalog-power-case-stage";
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private PowerCaseSharedStageApplication() { }
    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !"--source-root".equals(args[0]))
            throw new IllegalArgumentException("Use --source-root <repository-root>; stage export has no apply/publication mode");
        Path root = Path.of(args[1]).toRealPath();
        var stage = SharedPowerCaseStage.load(root);
        Path output = root.resolve(OUTPUT).normalize();
        if (!output.startsWith(root)) throw new IllegalArgumentException("Stage output must stay inside the repository");
        for (Path path = output; !path.equals(root); path = path.getParent())
            if (Files.isSymbolicLink(path)) throw new IllegalArgumentException("Stage output must not use symlinks");
        Files.createDirectories(output);
        write(output.resolve("power-case-stage.json"), stage.envelope());
        write(output.resolve("catalog-power-case-stage.json"), stage.envelope().catalog());
        write(output.resolve("reference-prices-power-case-stage.json"), stage.references());
        write(output.resolve("report.json"), Map.of("mode", SharedPowerCaseStage.MODE, "products", 315,
                "currentPrices", 83, "referencesPreserved", 227, "newUnpricedProducts", 5,
                "publicationApproved", false, "databaseApplyApproved", false, "databaseAccess", 0, "deployments", 0));
        System.out.println("PSU/CASE shared stage: products=315 prices=83 preservedReferences=227 DB/deploy=0 output=" + OUTPUT);
    }
    private static void write(Path target, Object value) throws Exception {
        if (Files.isSymbolicLink(target)) throw new IllegalArgumentException("Stage output must not use symlinks");
        Files.writeString(target, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value)
                .replace("\r\n", "\n") + "\n", StandardCharsets.UTF_8);
    }
}
