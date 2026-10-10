package com.pcupgradelab.catalog.shared;

import com.pcupgradelab.catalog.CatalogReferenceEstimate;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import tools.jackson.databind.json.JsonMapper;
import static com.pcupgradelab.catalog.shared.SharedReferencePriceValidation.require;

/** Explicit read-only HTTPS verification after an approved publication. No Spring, JDBC, migrations or deployment. */
public final class DeployedReferencePriceVerification {
    private static final URI APPROVED_ORIGIN = URI.create("https://pc-upgrade-shared-prices.joony1024.workers.dev");
    private DeployedReferencePriceVerification() { }
    public static void main(String[] args) throws Exception {
        Path root = Path.of("..").toAbsolutePath().normalize();
        URI origin = null;
        String tokenEnvironment = "CATALOG_SHARED_PRICES_API_TOKEN";
        for (int i = 0; i < args.length; i++) {
            require(i + 1 < args.length, "Verification option requires a value");
            switch (args[i++]) {
                case "--source-root" -> root = Path.of(args[i]).toAbsolutePath().normalize();
                case "--base-url" -> origin = URI.create(args[i]);
                case "--token-environment" -> tokenEnvironment = args[i];
                default -> throw new IllegalArgumentException("Unknown deployed-reference verification option");
            }
        }
        require(origin != null && APPROVED_ORIGIN.equals(SharedPriceClient.validateBaseUrl(origin)),
                "Verification is limited to the existing approved HTTPS Worker");
        require(tokenEnvironment.matches("[A-Z][A-Z0-9_]{0,127}"), "Verification token environment name is invalid");
        String token = System.getenv(tokenEnvironment);
        var clock = Clock.systemUTC();
        var catalog = SharedCatalogSnapshot.loadActive(root);
        var expected = SharedReferencePriceSnapshot.load(root, catalog);
        var review = SharedReferencePriceSnapshot.loadReview(root, catalog);
        require(Files.isRegularFile(root.resolve(SharedReferencePriceSnapshot.ACTIVE_FILE)) && expected.publicationApproved()
                && expected.products().equals(review.products()) && expected.referenceVersion().equals(review.referenceVersion()),
                "Verification requires the separately approved active publication and unchanged review");
        var referenceClient = new SharedReferencePriceClient(origin, Duration.ofSeconds(2), 65536, catalog, clock, token);
        var expectedById = new LinkedHashMap<String, CatalogReferenceEstimate>();
        for (var product : expected.products()) expectedById.put(product.identity().canonicalId(), product.referenceEstimate());
        var ids = List.copyOf(catalog.products().keySet());
        var references = new LinkedHashMap<String, CatalogReferenceEstimate>();
        int noReferences = 0;
        for (int start = 0; start < ids.size(); start += referenceClient.batchSize()) {
            var result = referenceClient.fetch(new LinkedHashSet<>(ids.subList(start, Math.min(ids.size(), start + referenceClient.batchSize()))));
            require(expected.referenceVersion().equals(result.referenceVersion()), "Deployed reference publication differs");
            for (var item : result.items()) {
                var approved = expectedById.get(item.canonicalId());
                require(Objects.equals(approved, item.referenceEstimate())
                        && item.status() == (approved == null ? SharedReferencePriceDtos.Status.NO_REFERENCE : SharedReferencePriceDtos.Status.OK),
                        "Deployed reference evidence or scope differs");
                if (approved == null) noReferences++; else references.put(item.canonicalId(), item.referenceEstimate());
            }
        }
        require(references.size() == 227 && noReferences == 81, "Deployed reference coverage differs");
        int currentPrices = verifyCurrent(origin, token, catalog, clock);
        var previous = SharedCatalogSnapshot.loadFull(root);
        int previousCurrentPrices = verifyCurrent(origin, token, previous, clock);
        require(currentPrices == 81 && previousCurrentPrices == 80, "Deployed current observations changed");
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
        String query = "?canonicalIds=" + ids.getFirst();
        for (String authorization : new String[] { null, "Bearer " + ("0".repeat(64).equals(token) ? "1".repeat(64) : "0".repeat(64)) }) {
            var request = HttpRequest.newBuilder(origin.resolve(SharedReferencePriceClient.API_PATH + query)).timeout(Duration.ofSeconds(2));
            if (authorization != null) request.header("Authorization", authorization);
            require(http.send(request.GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode() == 401,
                    "Deployed reference endpoint must reject missing and incorrect authentication");
        }
        var basis = new LinkedHashMap<String, Long>();
        for (var value : CatalogReferenceEstimate.Basis.values()) basis.put(value.name(), references.values().stream()
                .filter(r -> r.basis() == value).count());
        var report = new LinkedHashMap<String, Object>();
        report.put("verifiedAt", clock.instant()); report.put("origin", APPROVED_ORIGIN.toString());
        report.put("catalogVersion", catalog.version()); report.put("referenceVersion", expected.referenceVersion());
        report.put("products", catalog.products().size()); report.put("referencesVerified", references.size());
        report.put("currentPricesPreserved", currentPrices); report.put("previous307CurrentPricesPreserved", previousCurrentPrices);
        report.put("currentProductsWithNoReference", noReferences); report.put("basis", basis);
        report.put("missingAndIncorrectAuthentication", 401); report.put("publicationApproved", true);
        report.put("databaseChanges", 0); report.put("deployments", 0); report.put("serverRestarts", 0);
        var output = Files.createDirectories(root.resolve("backend/build/catalog-reference-publication"));
        Files.write(output.resolve("deployed-reference-verification.json"),
                JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsBytes(report));
        System.out.println("Deployed HTTPS references verified: 227 references; 81 existing current prices and legacy 307/80 preserved; auth=401; DB/deploy/restart=0.");
    }
    private static int verifyCurrent(URI origin, String token, SharedCatalogSnapshot catalog, Clock clock) {
        var client = new SharedPriceClient(origin, Duration.ofSeconds(2), 65536, catalog, clock, SharedPriceFreshness.defaults(), token);
        var ids = List.copyOf(catalog.products().keySet());
        int prices = 0;
        for (int start = 0; start < ids.size(); start += client.batchSize()) {
            var result = client.fetch(new LinkedHashSet<>(ids.subList(start, Math.min(ids.size(), start + client.batchSize()))));
            for (var item : result.items()) {
                var expected = catalog.products().get(item.canonicalId()).price();
                require(Objects.equals(expected, item.price()) && item.status() == (expected == null
                        ? SharedPriceDtos.Status.NO_PRICE : SharedPriceDtos.Status.OK), "Deployed current observation differs");
                if (item.price() != null) prices++;
            }
        }
        return prices;
    }
}
