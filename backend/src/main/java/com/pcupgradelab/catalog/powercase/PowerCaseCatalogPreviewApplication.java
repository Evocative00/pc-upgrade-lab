package com.pcupgradelab.catalog.powercase;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import tools.jackson.databind.json.JsonMapper;

/** DB 없는 명시적 미리보기. 결과는 무시되는 backend/build 경로에만 쓴다. 적용·게시 모드는 없다. */
public final class PowerCaseCatalogPreviewApplication {
    public static final String OUTPUT = "backend/build/catalog-power-case-preview";
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private PowerCaseCatalogPreviewApplication() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !"--source-root".equals(args[0]))
            throw new IllegalArgumentException("Use --source-root <repository-root>; --apply and database access are unavailable");
        Path root = Path.of(args[1]).toRealPath();
        var plan = new PowerCaseCatalogPreviewLoader().load(root);
        Path output = root.resolve(OUTPUT).normalize();
        if (!output.startsWith(root)) throw new IllegalArgumentException("Preview output must stay inside the repository");
        for (Path path = output; !path.equals(root); path = path.getParent())
            if (Files.isSymbolicLink(path)) throw new IllegalArgumentException("Preview output must not use symlinks");
        Files.createDirectories(output);
        write(output.resolve("registrations-proposed.json"), new PowerCaseCatalogPreviewLoader.Proposal<>(1,
                PowerCaseCatalogPreviewLoader.MODE, false, false, plan.registrations()));
        write(output.resolve("prices-proposed.json"), new PowerCaseCatalogPreviewLoader.Proposal<>(1,
                PowerCaseCatalogPreviewLoader.MODE, false, false, plan.prices()));
        write(output.resolve("central-extension-proposed.json"), plan.centralExtension());
        write(output.resolve("report.json"), plan.report());
        System.out.println("파워·케이스 미리보기: 파워=4 케이스=3 가격후보=" + plan.prices().size()
                + " 가격보류=" + plan.report().priceHeld() + " DB접근=0 DB변경=0 게시=0 결과=" + OUTPUT);
    }

    private static void write(Path target, Object value) throws Exception {
        if (Files.isSymbolicLink(target)) throw new IllegalArgumentException("Preview output must not use symlinks");
        Files.writeString(target, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value)
                .replace("\r\n", "\n") + "\n", StandardCharsets.UTF_8);
    }
}
