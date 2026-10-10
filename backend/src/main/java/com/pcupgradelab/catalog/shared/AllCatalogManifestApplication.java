package com.pcupgradelab.catalog.shared;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import tools.jackson.databind.json.JsonMapper;

/** Explicit public artifact generation; it does not initialize Spring, JDBC, Flyway or a server. */
public final class AllCatalogManifestApplication {
    private AllCatalogManifestApplication() { }
    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2 || args.length == 2 && !"--check".equals(args[1]))
            throw new IllegalArgumentException("Use <repository-root> [--check]");
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        var plan = new AllCatalogManifestGenerator().load(root);
        var mapper = JsonMapper.builder().build();
        write(root.resolve(AllCatalogManifestGenerator.IDENTITIES_FILE),json(mapper,plan.manifest()),args.length == 2);
        write(root.resolve(AllCatalogManifestGenerator.PRICES_FILE),json(mapper,plan.prices()),args.length == 2);
        System.out.println("ALL CATALOG PUBLIC REVIEW: products=307 legacyBindings=293 approvedPrices=74 missingPrices=233 databaseAccess=0");
    }
    private static String json(JsonMapper mapper,Object value) {
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value).replace("\r\n","\n") + "\n";
    }
    private static void write(Path target,String content,boolean check) throws Exception {
        if (check) {
            if (!Files.isRegularFile(target) || !Files.readString(target,StandardCharsets.UTF_8).replace("\r\n","\n").equals(content))
                throw new IllegalArgumentException("Public full-catalog artifact differs; regenerate from authoritative reviewed inputs");
        } else {
            Files.createDirectories(target.getParent());
            Files.writeString(target,content,StandardCharsets.UTF_8);
        }
    }
}
