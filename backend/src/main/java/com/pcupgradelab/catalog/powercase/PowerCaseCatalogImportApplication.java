package com.pcupgradelab.catalog.powercase;

import com.pcupgradelab.BackendApplication;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import tools.jackson.databind.json.JsonMapper;

/** 기본 경로는 공개 파일 미리보기다. DB 점검·반영은 별도 명시 옵션에서만 시작한다. */
public final class PowerCaseCatalogImportApplication {
    public static final String OUTPUT = "backend/build/catalog-power-case-import";
    private PowerCaseCatalogImportApplication() { }
    public static void main(String[] args) throws Exception {
        var options = Options.parse(args);
        var loader = new PowerCaseCatalogImportLoader();
        // Exact approval validation precedes Spring startup, private configuration and any JDBC connection.
        var plan = options.apply() ? loader.loadApproved(options.sourceRoot(),options.approvalFile()) : loader.loadForCheck(options.sourceRoot());
        var mapper = JsonMapper.builder().build();
        if (!options.checkDb() && !options.apply()) {
            PowerCaseCatalogPreviewApplication.main(new String[]{"--source-root",options.sourceRoot().toString()});
            Path root = options.sourceRoot().toRealPath(); Path output = root.resolve(OUTPUT);
            for (Path current=output; !current.equals(root); current=current.getParent())
                if (Files.isSymbolicLink(current)) throw new IllegalArgumentException("Import preview output must not use symlinks");
            Files.createDirectories(output);
            Path proposal = output.resolve("database-approval-proposed.json");
            if (Files.isSymbolicLink(proposal)) throw new IllegalArgumentException("Approval proposal must not use symlinks");
            Files.writeString(proposal,mapper.writerWithDefaultPrettyPrinter().writeValueAsString(loader.approvalProposal(plan))
                    .replace("\r\n","\n") + "\n",StandardCharsets.UTF_8);
            System.out.println("PSU_CASE_DB_FREE_PREVIEW: products=7 priceCandidates=" + plan.preview().prices().size()
                    + " databaseAccess=0 databaseWrites=0 publication=0 normalizedPreviewSha256=" + plan.previewSha256());
            return;
        }
        var app = new SpringApplication(BackendApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        try (var context = app.run("--spring.profiles.active=local", "--spring.main.web-application-type=none",
                "--spring.flyway.enabled=false", "--spring.sql.init.mode=never", "--spring.jpa.hibernate.ddl-auto=validate",
                "--spring.autoconfigure.exclude=org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientAutoConfiguration",
                "--catalog.seed.enabled=false", "--catalog.cpu-memory.seed.enabled=false", "--catalog.motherboard-cpu.seed.enabled=false",
                "--catalog.price-import.enabled=false", "--catalog.shared-prices.enabled=false")) {
            var service = context.getBean(PowerCaseCatalogImportService.class);
            var result = options.checkDb() ? service.preview(plan) : service.apply(plan);
            System.out.println(options.apply() ? "PSU_CASE_DB_TRANSACTION_COMMITTED" : "PSU_CASE_READ_ONLY_PREFLIGHT_COMPLETE: databaseWrites=0");
            System.out.println("PSU_CASE_IMPORT_RESULT " + mapper.writeValueAsString(result));
        }
    }
    record Options(Path sourceRoot, boolean checkDb, boolean apply, Path approvalFile) {
        static Options parse(String[] args) {
            if (args == null) throw new IllegalArgumentException("Arguments are required");
            Path root=null, approval=null; boolean check=false, apply=false;
            for (int i=0; i<args.length; i++) {
                if (args[i] == null) throw new IllegalArgumentException("Arguments cannot be null");
                switch (args[i]) {
                    case "--source-root" -> {
                        if (root != null || ++i >= args.length || args[i] == null || args[i].isBlank())
                            throw new IllegalArgumentException("Use one --source-root <repository-root>");
                        root=Path.of(args[i]).toAbsolutePath().normalize();
                    }
                    case "--approval-file" -> {
                        if (approval != null || ++i >= args.length || args[i] == null || args[i].isBlank())
                            throw new IllegalArgumentException("Use one --approval-file <separately-approved-manifest>");
                        approval=Path.of(args[i]);
                    }
                    case "--check-db" -> { if (check || apply) throw new IllegalArgumentException("Choose only one DB mode"); check=true; }
                    case "--apply" -> { if (check || apply) throw new IllegalArgumentException("Choose only one DB mode"); apply=true; }
                    default -> throw new IllegalArgumentException("Unsupported PSU/CASE import argument");
                }
            }
            if (root == null) throw new IllegalArgumentException("--source-root is required");
            if (apply != (approval != null)) throw new IllegalArgumentException("--apply requires a separate --approval-file; approval files are apply-only");
            return new Options(root,check,apply,approval);
        }
    }
}
