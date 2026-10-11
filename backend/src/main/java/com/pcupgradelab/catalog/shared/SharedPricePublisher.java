package com.pcupgradelab.catalog.shared;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import tools.jackson.databind.json.JsonMapper;

/** Explicit loopback prototype: approved public prices only, with no Spring/JDBC/Flyway startup. */
public final class SharedPricePublisher implements AutoCloseable {
    public static final String PATH = "/api/v1/prices";
    public static final String V2_PATH = "/api/v2/prices";
    private final HttpServer server;
    private final ThreadPoolExecutor executor;
    private final SharedCatalogSnapshot snapshot;
    private final Clock clock;
    private final SharedPriceFreshness freshness;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final AtomicBoolean closed = new AtomicBoolean();

    private SharedPricePublisher(SharedCatalogSnapshot snapshot, Clock clock, SharedPriceFreshness freshness, int port)
            throws IOException {
        this.snapshot = Objects.requireNonNull(snapshot);
        this.clock = Objects.requireNonNull(clock);
        this.freshness = Objects.requireNonNull(freshness);
        if (port < 0 || port > 65535) throw new IllegalArgumentException("Invalid publisher port");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 32);
        executor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64), task -> {
            Thread thread = new Thread(task, "shared-price-http");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.CallerRunsPolicy());
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
    }

    public static SharedPricePublisher start(SharedCatalogSnapshot snapshot, Clock clock,
                                             SharedPriceFreshness freshness, int port) {
        try { return new SharedPricePublisher(snapshot, clock, freshness, port); }
        catch (IOException ex) { throw new IllegalStateException("Shared price publisher cannot bind loopback port", ex); }
    }
    public URI baseUri() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort()); }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!snapshot.apiPath().equals(exchange.getRequestURI().getRawPath())) {
                send(exchange, 404, Map.of("error", "NOT_FOUND"));
                return;
            }
            if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "GET");
                send(exchange, 405, Map.of("error", "METHOD_NOT_ALLOWED"));
                return;
            }
            List<String> ids;
            try { ids = ids(exchange.getRequestURI().getRawQuery()); }
            catch (IllegalArgumentException ex) {
                send(exchange, 400, Map.of("error", "INVALID_CANONICAL_IDS"));
                return;
            }
            var now = clock.instant();
            var items = new ArrayList<SharedPriceDtos.Item>();
            for (String id : ids) {
                var entry = snapshot.products().get(id);
                if (entry == null) {
                    items.add(new SharedPriceDtos.Item(id, null, null, SharedPriceDtos.Status.UNKNOWN_PRODUCT,
                            SharedPriceDtos.Freshness.NO_PRICE));
                } else {
                    items.add(new SharedPriceDtos.Item(id, entry.identity(), entry.price(), entry.price() == null
                            ? SharedPriceDtos.Status.NO_PRICE : SharedPriceDtos.Status.OK,
                            freshness.classify(entry.price() == null ? null : entry.price().observedAt(), now)));
                }
            }
            send(exchange, 200, snapshot.isFull()
                    ? new SharedPriceDtos.EnvelopeV2(2, snapshot.version(), snapshot.priceVersion(), now, freshness.toView(), items)
                    : new SharedPriceDtos.Envelope(1, snapshot.version(), now, freshness.toView(), items));
        }
    }

    private static List<String> ids(String rawQuery) {
        if (rawQuery == null || rawQuery.length() > 8192 || rawQuery.contains("&"))
            throw new IllegalArgumentException("Single canonicalIds parameter required");
        String query = URLDecoder.decode(rawQuery, StandardCharsets.UTF_8);
        if (!query.startsWith("canonicalIds=") || query.indexOf('=', 13) >= 0)
            throw new IllegalArgumentException("Single canonicalIds parameter required");
        String[] values = query.substring("canonicalIds=".length()).split(",", -1);
        if (values.length == 0 || values.length > 100) throw new IllegalArgumentException("1..100 IDs required");
        var seen = new HashSet<String>();
        for (String value : values) {
            if (!UUID.fromString(value).toString().equals(value) || !seen.add(value))
                throw new IllegalArgumentException("Canonical UUIDs must be unique and normalized");
        }
        return List.of(values);
    }

    private void send(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = mapper.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        server.stop(0);
        executor.shutdownNow();
    }

    public static void main(String[] args) throws InterruptedException {
        int port = 8081;
        boolean check = false;
        for (int i = 0; i < args.length; i++) {
            if ("--check".equals(args[i]) && !check) check = true;
            else if ("--port".equals(args[i]) && i + 1 < args.length) port = Integer.parseInt(args[++i]);
            else throw new IllegalArgumentException("Use --check or --port <1..65535>");
        }
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Port must be 1..65535");
        var snapshot = SharedCatalogSnapshot.load();
        if (check) {
            System.out.println("Shared snapshot verified: products=" + snapshot.products().size()
                    + " prices=" + snapshot.products().values().stream().filter(entry -> entry.price() != null).count()
                    + " databaseAccess=0 version=" + snapshot.version());
            return;
        }
        var stopped = new CountDownLatch(1);
        try (var publisher = start(snapshot, Clock.systemUTC(), SharedPriceFreshness.defaults(), port)) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> { publisher.close(); stopped.countDown(); }));
            System.out.println("Shared price prototype listening at " + publisher.baseUri() + snapshot.apiPath());
            stopped.await();
        }
    }
}
