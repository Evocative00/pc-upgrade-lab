package com.pcupgradelab.catalog.shared;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Builds public Worker inputs and reference responses through the real Java HTTP provider. */
public final class SharedCatalogExportApplication {
    public static final String RETAIL_CATALOG_FILE = "catalog-retail-approved.json";
    public static final String RETAIL_CONTRACTS_FILE = "contract-fixtures-retail-approved.json";
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private SharedCatalogExportApplication() { }

    public record Catalog(int schemaVersion, String catalogVersion, SharedPriceDtos.Policy policy,
                          List<SharedCatalogSnapshot.Entry> products) { }
    public record CatalogV2(int schemaVersion, String catalogVersion, String priceVersion,
                            SharedPriceDtos.Policy policy, List<SharedCatalogSnapshot.Entry> products) { }
    public record Fixture(String name, Instant now, List<String> canonicalIds, JsonNode expected,
                          Integer errorStatus) { }
    public record Contracts(int schemaVersion, String catalogVersion, List<Fixture> fixtures) { }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) throw new IllegalArgumentException("Use <repository-root> [--check] [--prices <reviewed-price-file> | --retail-approved]");
        Path repository = Path.of(args[0]).toAbsolutePath().normalize();
        boolean check = false;
        boolean retailApproved = false;
        Path prices = null;
        for (int i = 1; i < args.length; i++) {
            if ("--check".equals(args[i]) && !check) check = true;
            else if ("--retail-approved".equals(args[i]) && !retailApproved) retailApproved = true;
            else if ("--prices".equals(args[i]) && prices == null && i + 1 < args.length)
                prices = Path.of(args[++i]).toAbsolutePath().normalize();
            else throw new IllegalArgumentException("Use <repository-root> [--check] [--prices <reviewed-price-file> | --retail-approved]");
        }
        if (retailApproved) {
            if (prices != null) throw new IllegalArgumentException("Approved retail export cannot use an arbitrary price override");
            exportApprovedRetail(repository, check);
            return;
        }
        export(repository, SharedCatalogSnapshot.loadPilot(repository), check);
        export(repository, prices == null ? SharedCatalogSnapshot.loadFull(repository)
                : SharedCatalogSnapshot.loadFull(repository, prices), check);
    }

    private static void export(Path repository, SharedCatalogSnapshot snapshot, boolean check) throws Exception {
        exportTo(repository.resolve("workers/catalog-prices/generated"), snapshot, check);
    }

    /** Reviewed additions are written only into ignored build output, never production Worker inputs. */
    public static void exportReviewedPreview(Path repository, SharedCatalogSnapshot snapshot) throws Exception {
        exportTo(repository.resolve("backend/build/catalog-retail-preview/worker"), snapshot, false);
    }

    /** Explicit approved 308-product export has distinct files; the 307-product and v1 inputs remain untouched. */
    public static void exportApprovedRetail(Path repository) throws Exception {
        exportApprovedRetail(repository, false);
    }

    private static void exportApprovedRetail(Path repository, boolean check) throws Exception {
        exportTo(repository.resolve("workers/catalog-prices/generated"), SharedCatalogSnapshot.loadActive(repository),
                check, RETAIL_CATALOG_FILE, RETAIL_CONTRACTS_FILE);
    }

    private static void exportTo(Path output, SharedCatalogSnapshot snapshot, boolean check) throws Exception {
        exportTo(output, snapshot, check, snapshot.isFull() ? "catalog-v2.json" : "catalog.json",
                snapshot.isFull() ? "contract-fixtures-v2.json" : "contract-fixtures.json");
    }

    private static void exportTo(Path output, SharedCatalogSnapshot snapshot, boolean check,
                                 String catalogFile, String contractsFile) throws Exception {
        var entries = snapshot.products().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey())
                .map(java.util.Map.Entry::getValue).toList();
        Object catalog = snapshot.isFull()
                ? new CatalogV2(2, snapshot.version(), snapshot.priceVersion(), SharedPriceFreshness.defaults().toView(), entries)
                : new Catalog(1, snapshot.version(), SharedPriceFreshness.defaults().toView(), entries);
        var fixtures = referenceResponses(snapshot, entries);
        String catalogJson = json(catalog);
        String contractsJson = json(new Contracts(snapshot.schemaVersion(), snapshot.version(), fixtures));
        if (!check) Files.createDirectories(output);
        writeOrCheck(output.resolve(catalogFile), catalogJson, check);
        writeOrCheck(output.resolve(contractsFile), contractsJson, check);
        System.out.println("Worker public artifacts " + (check ? "verified" : "exported")
                + ": products=" + entries.size() + " prices=" + entries.stream().filter(entry -> entry.price() != null).count()
                + " javaHttpFixtures=" + fixtures.size()
                + " databaseAccess=0 version=" + snapshot.version());
    }

    private static List<Fixture> referenceResponses(SharedCatalogSnapshot snapshot,
                                                   List<SharedCatalogSnapshot.Entry> entries) throws Exception {
        var fixtures = new ArrayList<Fixture>();
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        Instant latest = entries.stream().filter(entry -> entry.price() != null)
                .map(entry -> entry.price().observedAt()).max(Instant::compareTo).orElseThrow();
        Instant allFresh = latest.plusSeconds(3600);
        List<String> allIds = entries.stream().map(entry -> entry.identity().canonicalId()).toList();
        addChunks(fixtures, snapshot, client, "all-approved-fresh", allFresh, allIds);
        addChunks(fixtures, snapshot, client, "unpriced-products", allFresh, entries.stream()
                .filter(entry -> entry.price() == null).map(entry -> entry.identity().canonicalId()).toList());
        var unknown = new ArrayList<String>();
        for (int i = 1; i <= 101; i++) unknown.add(new UUID(0, i).toString());
        fixtures.add(reference(snapshot, client, "unknown-product", allFresh, unknown.subList(0, 1)));
        fixtures.add(reference(snapshot, client, "maximum-100-ids", allFresh, unknown.subList(0, 100)));
        fixtures.add(reference(snapshot, client, "reject-101-ids", allFresh, unknown));
        if (snapshot.isFull()) {
            var largest = entries.stream().sorted(java.util.Comparator.comparingInt((SharedCatalogSnapshot.Entry entry) ->
                    MAPPER.writeValueAsBytes(new SharedPriceDtos.Item(entry.identity().canonicalId(), entry.identity(), entry.price(),
                            entry.price() == null ? SharedPriceDtos.Status.NO_PRICE : SharedPriceDtos.Status.OK,
                            SharedPriceDtos.Freshness.STALE)).length).reversed()).limit(100)
                    .map(entry -> entry.identity().canonicalId()).toList();
            var maximum = reference(snapshot, client, "largest-100-known-products", allFresh, largest);
            if (MAPPER.writeValueAsBytes(maximum.expected()).length > 65536)
                throw new IllegalArgumentException("Maximum approved Worker response exceeds the client byte limit");
            fixtures.add(maximum);
        }
        for (var entry : entries) {
            if (entry.price() == null) continue;
            var observed = entry.price().observedAt();
            for (var boundary : List.of(new Boundary("stale", 172800), new Boundary("expired", 604800))) {
                for (int offset : List.of(-1, 0, 1)) {
                    Instant now = observed.plusSeconds(boundary.seconds()).plusMillis(offset);
                    fixtures.add(reference(snapshot, client, entry.identity().canonicalId() + "-"
                            + boundary.name() + "-" + offset, now, List.of(entry.identity().canonicalId())));
                }
            }
        }
        return List.copyOf(fixtures);
    }

    private record Boundary(String name, long seconds) { }
    private static void addChunks(List<Fixture> fixtures, SharedCatalogSnapshot snapshot, HttpClient client,
                                  String name, Instant now, List<String> ids) throws Exception {
        for (int start = 0; start < ids.size(); start += 100)
            fixtures.add(reference(snapshot, client, ids.size() <= 100 ? name : name + "-" + start / 100,
                    now, ids.subList(start, Math.min(start + 100, ids.size()))));
    }
    private static Fixture reference(SharedCatalogSnapshot snapshot, HttpClient client, String name,
                                     Instant now, List<String> ids) throws Exception {
        try (var publisher = SharedPricePublisher.start(snapshot, Clock.fixed(now, ZoneOffset.UTC),
                SharedPriceFreshness.defaults(), 0)) {
            URI uri = publisher.baseUri().resolve(snapshot.apiPath() + "?canonicalIds=" + String.join(",", ids));
            var response = client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(2)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 && response.statusCode() != 400)
                throw new IllegalStateException("Unexpected Java reference response");
            return new Fixture(name, now, List.copyOf(ids), response.statusCode() == 200
                    ? MAPPER.readTree(response.body()) : null, response.statusCode() == 200 ? null : response.statusCode());
        }
    }

    private static String json(Object value) {
        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value).replace("\r\n", "\n") + "\n";
    }
    private static void writeOrCheck(Path target, String content, boolean check) throws IOException {
        if (check) {
            if (!Files.isRegularFile(target) || !Files.readString(target, StandardCharsets.UTF_8)
                    .replace("\r\n", "\n").equals(content))
                throw new IllegalArgumentException("Generated Worker artifacts differ; run exportSharedCatalog first");
        } else Files.writeString(target, content, StandardCharsets.UTF_8);
    }
}
