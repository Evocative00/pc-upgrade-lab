package com.pcupgradelab.catalog.shared;

import com.pcupgradelab.catalog.CatalogPriceStatus;
import com.pcupgradelab.catalog.CatalogProductView;
import com.pcupgradelab.catalog.CatalogReferenceEstimate;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import tools.jackson.databind.json.JsonMapper;
import static com.pcupgradelab.catalog.shared.SharedReferencePriceValidation.require;

/** Explicit loopback-only Java/Worker preview. Never starts Spring, touches a DB or deploys a Worker. */
public final class ReferencePricePreviewApplication {
    private ReferencePricePreviewApplication() { }
    public static void main(String[] args) throws Exception {
        Path root = Path.of("..").toAbsolutePath().normalize();
        URI baseUrl = null;
        String tokenEnvironment = "REFERENCE_PREVIEW_TOKEN";
        for (int i = 0; i < args.length; i++) {
            require(i + 1 < args.length, "Preview option requires a value");
            switch (args[i++]) {
                case "--source-root" -> root = Path.of(args[i]).toAbsolutePath().normalize();
                case "--base-url" -> baseUrl = URI.create(args[i]);
                case "--token-environment" -> tokenEnvironment = args[i];
                default -> throw new IllegalArgumentException("Unknown reference preview option");
            }
        }
        require(baseUrl != null && "http".equals(SharedPriceClient.validateBaseUrl(baseUrl).getScheme()),
                "Reference preview only accepts a literal loopback HTTP origin");
        require(tokenEnvironment.matches("[A-Z][A-Z0-9_]{0,127}"), "Preview token environment name is invalid");
        var token = System.getenv(tokenEnvironment);
        var catalog = SharedCatalogSnapshot.loadActive(root);
        var expected = SharedReferencePriceSnapshot.loadReview(root, catalog);
        require(!expected.publicationApproved(), "This command is limited to an unpublished local preview");
        var clock = Clock.systemUTC();
        var referenceClient = new SharedReferencePriceClient(baseUrl, Duration.ofSeconds(2), 65536, catalog, clock, token);
        var currentClient = new SharedPriceClient(baseUrl, Duration.ofSeconds(2), 65536, catalog, clock,
                SharedPriceFreshness.defaults(), token);
        var ids = List.copyOf(catalog.products().keySet());
        var current = new LinkedHashMap<String, CatalogProductView.CurrentPrice>();
        var currentStatuses = new LinkedHashMap<String, CatalogProductView.PriceStatus>();
        for (int start = 0; start < ids.size(); start += currentClient.batchSize()) {
            var result = currentClient.fetch(new LinkedHashSet<>(ids.subList(start, Math.min(ids.size(), start + currentClient.batchSize()))));
            for (var item : result.items()) {
                require(Objects.equals(item.price(), catalog.products().get(item.canonicalId()).price()),
                        "Preview changed an existing current observation");
                if (item.price() != null) current.put(item.canonicalId(), item.price());
                var checkedAt = clock.instant();
                currentStatuses.put(item.canonicalId(), new CatalogProductView.PriceStatus("SHARED", item.status().name(),
                        item.freshness().name(), catalog.version(), checkedAt, checkedAt,
                        item.status() == SharedPriceDtos.Status.OK && item.freshness() != SharedPriceDtos.Freshness.EXPIRED));
            }
        }
        var references = new LinkedHashMap<String, CatalogReferenceEstimate>();
        var expectedIds = expected.products().stream().map(p -> p.identity().canonicalId()).toList();
        for (int start = 0; start < expectedIds.size(); start += referenceClient.batchSize()) {
            var result = referenceClient.fetch(new LinkedHashSet<>(expectedIds.subList(start,
                    Math.min(expectedIds.size(), start + referenceClient.batchSize()))));
            require(expected.referenceVersion().equals(result.referenceVersion()), "Preview reference publication differs");
            for (var item : result.items()) {
                require(item.status() == SharedReferencePriceDtos.Status.OK, "Preview reference is missing");
                references.put(item.canonicalId(), item.referenceEstimate());
            }
        }
        for (var product : expected.products()) require(product.referenceEstimate().equals(references.get(product.identity().canonicalId())),
                "Preview reference differs from reviewed evidence");
        var products = new ArrayList<CatalogProductView>();
        var counts = new LinkedHashMap<String, Integer>();
        var now = clock.instant();
        for (var entry : catalog.products().values()) {
            var identity = entry.identity();
            var id = "preview-private-" + products.size();
            products.add(new CatalogProductView(id, identity.type(), identity.manufacturer(), identity.modelName(),
                    identity.partNumber(), identity.verificationStatus(), identity.active(), now, now,
                    new CatalogProductView.ReferencePrice(null, CatalogPriceStatus.UNCONFIRMED, now), current.get(identity.canonicalId()),
                    identity.canonicalId(), null, identity.identityKind(), identity.role(), currentStatuses.get(identity.canonicalId())));
            if (identity.moduleCount() != null) counts.put(id, identity.moduleCount());
        }
        var enriched = new SharedReferencePriceAdapter(catalog, referenceClient).enrich(products, counts);
        require(current.size() == 81 && references.size() == 227
                && enriched.stream().filter(p -> p.referenceEstimate() != null).count() == 227,
                "Preview coverage must remain 81 current and 227 reference prices");
        var byBasis = new LinkedHashMap<String, Long>();
        for (var basis : CatalogReferenceEstimate.Basis.values()) byBasis.put(basis.name(), references.values().stream()
                .filter(r -> r.basis() == basis).count());
        var report = new LinkedHashMap<String, Object>();
        report.put("catalogVersion", catalog.version()); report.put("referenceVersion", expected.referenceVersion());
        report.put("products", enriched.size()); report.put("currentPricesPreserved", current.size());
        report.put("referencesVerified", references.size()); report.put("basis", byBasis);
        report.put("databaseChanges", 0); report.put("deployments", 0); report.put("serverRestarts", 0);
        report.put("publicationApproved", false);
        var directory = Files.createDirectories(root.resolve("backend/build/catalog-reference-preview"));
        var mapper = JsonMapper.builder().build();
        Files.write(directory.resolve("backend-contract-verification.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(report));
        Files.write(directory.resolve("catalog-products-api-preview.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(enriched));
        System.out.println("Reference preview: 308 products; existing 81 current prices preserved; 227 references verified; DB/deploy/restart=0.");
    }
}
