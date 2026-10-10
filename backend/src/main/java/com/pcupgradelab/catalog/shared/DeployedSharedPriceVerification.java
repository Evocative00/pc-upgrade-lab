package com.pcupgradelab.catalog.shared;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;

/** Explicit read-only verification of the approved HTTPS Worker; no Spring, JDBC or Flyway. */
public final class DeployedSharedPriceVerification {
    private DeployedSharedPriceVerification() { }
    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2 || (args.length == 2
                && !"--full".equals(args[1]) && !"--active".equals(args[1])))
            throw new IllegalArgumentException("Use <deployed-workers-dev-origin> [--full | --active]");
        URI origin = SharedPriceClient.validateBaseUrl(URI.create(args[0]));
        if (!"https".equals(origin.getScheme()) || !origin.getHost()
                .matches("pc-upgrade-shared-prices\\.[a-z0-9-]+\\.workers\\.dev") || origin.getPort() != -1)
            throw new IllegalArgumentException("Verification requires the approved HTTPS workers.dev origin");
        String token = System.getenv("CATALOG_SHARED_PRICES_API_TOKEN");
        var snapshot = args.length == 2 ? ("--active".equals(args[1])
                ? SharedCatalogSnapshot.loadActive() : SharedCatalogSnapshot.loadFull()) : SharedCatalogSnapshot.loadPilot();
        var client = new SharedPriceClient(origin, Duration.ofSeconds(2), 65536, snapshot,
                Clock.systemUTC(), SharedPriceFreshness.defaults(), token);
        var allIds = new ArrayList<>(snapshot.products().keySet());
        var results = new ArrayList<SharedPriceDtos.Item>();
        for (int start = 0; start < allIds.size(); start += client.batchSize())
            results.addAll(client.fetch(new LinkedHashSet<>(allIds.subList(start,
                    Math.min(start + client.batchSize(), allIds.size())))).items());
        long prices = results.stream().filter(item -> item.status() == SharedPriceDtos.Status.OK).count();
        long noPrices = results.stream().filter(item -> item.status() == SharedPriceDtos.Status.NO_PRICE).count();
        require(results.size() == snapshot.products().size() && prices + noPrices == results.size(),
                "Deployed Worker catalog identity scope differs");
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        String query = "?canonicalIds=" + snapshot.products().keySet().iterator().next();
        for (String authorization : new String[] { null, "Bearer " + ("0".repeat(64).equals(token)
                ? "1".repeat(64) : "0".repeat(64)) }) {
            var request = HttpRequest.newBuilder(origin.resolve(snapshot.apiPath() + query))
                    .timeout(Duration.ofSeconds(2));
            if (authorization != null) request.header("Authorization", authorization);
            var response = http.send(request.GET().build(), HttpResponse.BodyHandlers.discarding());
            require(response.statusCode() == 401, "Deployed Worker must reject missing and incorrect authentication");
        }
        var ids = new ArrayList<>(allIds.subList(0, Math.min(allIds.size(), 50)));
        int knownCount = ids.size();
        for (int i = 1; ids.size() < 100; i++) ids.add(new UUID(0, i).toString());
        var maximumBuilder = HttpRequest.newBuilder(origin.resolve(snapshot.apiPath()
                        + "?canonicalIds=" + String.join(",", ids)))
                .header("Authorization", "Bearer " + token).timeout(Duration.ofSeconds(2));
        if (snapshot.isFull()) maximumBuilder.header("X-Catalog-Version", snapshot.version());
        var request = maximumBuilder.GET().build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        require(response.statusCode() == 200 && response.body().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 65536
                && "no-store".equals(response.headers().firstValue("Cache-Control").orElse(""))
                && "nosniff".equals(response.headers().firstValue("X-Content-Type-Options").orElse("")),
                "Deployed Worker maximum batch or response headers differ");
        java.util.List<SharedPriceDtos.Item> maximum;
        try { maximum = snapshot.isFull()
                ? JsonMapper.builder().build().readValue(response.body(), SharedPriceDtos.EnvelopeV2.class).items()
                : JsonMapper.builder().build().readValue(response.body(), SharedPriceDtos.Envelope.class).items(); }
        catch (RuntimeException ex) { throw new IllegalStateException("Deployed Worker response contract differs"); }
        require(maximum.size() == 100 && maximum.stream()
                .filter(item -> item.status() == SharedPriceDtos.Status.UNKNOWN_PRODUCT).count() == 100 - knownCount,
                "Deployed Worker unknown-product batch differs");
        System.out.println("Deployed Java HTTPS client verified: products=" + results.size() + " prices=" + prices
                + " noPrice=" + noPrices + " unauthorized=401 maxBatch=100 databaseAccess=0 version=" + snapshot.version());
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
