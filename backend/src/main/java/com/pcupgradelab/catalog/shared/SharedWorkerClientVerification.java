package com.pcupgradelab.catalog.shared;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;

/** Explicit DB-free local integration probe; only loopback origins and synthetic test credentials. */
public final class SharedWorkerClientVerification {
    private SharedWorkerClientVerification() { }
    public static void main(String[] args) {
        if (args.length != 1) throw new IllegalArgumentException("Use <loopback-worker-base-url>");
        URI uri = URI.create(args[0]);
        if (!"http".equals(uri.getScheme()) || !"127.0.0.1".equals(uri.getHost()))
            throw new IllegalArgumentException("Worker integration verification requires IPv4 loopback HTTP");
        String token = System.getenv("CATALOG_SHARED_PRICES_API_TOKEN");
        if (!"1".repeat(64).equals(token))
            throw new IllegalArgumentException("Worker integration verification requires the synthetic test credential; use test:java-client");
        verify(uri, token, SharedCatalogSnapshot.loadPilot());
        verify(uri, token, SharedCatalogSnapshot.loadFull());
        verify(uri, token, SharedCatalogSnapshot.loadActive());
    }
    private static void verify(URI uri, String token, SharedCatalogSnapshot snapshot) {
        var client = new SharedPriceClient(uri, Duration.ofSeconds(2), 65536, snapshot,
                Clock.systemUTC(), SharedPriceFreshness.defaults(), token);
        var ids = new ArrayList<>(snapshot.products().keySet());
        var items = new ArrayList<SharedPriceDtos.Item>();
        for (int start = 0; start < ids.size(); start += client.batchSize())
            items.addAll(client.fetch(new LinkedHashSet<>(ids.subList(start,
                    Math.min(start + client.batchSize(), ids.size())))).items());
        long prices = items.stream().filter(item -> item.status() == SharedPriceDtos.Status.OK).count();
        long noPrices = items.stream().filter(item -> item.status() == SharedPriceDtos.Status.NO_PRICE).count();
        long expectedPrices = snapshot.products().values().stream().filter(entry -> entry.price() != null).count();
        if (items.size() != snapshot.products().size() || (!snapshot.isFull() && prices != expectedPrices)
                || prices + noPrices != items.size())
            throw new IllegalStateException("Worker integration scope differs from the reviewed export");
        System.out.println("Java client to local Worker verified: products=" + items.size() + " prices=" + prices
                + " noPrice=" + noPrices + " schemaVersion=" + snapshot.schemaVersion()
                + " databaseAccess=0 version=" + snapshot.version());
    }
}
