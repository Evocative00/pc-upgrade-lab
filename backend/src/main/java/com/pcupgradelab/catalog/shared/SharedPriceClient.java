package com.pcupgradelab.catalog.shared;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;

/** 승인된 canonical ID만 보내는 제한된 읽기 클라이언트. 쿠키·로컬 제품 ID·PC 자료는 전송하지 않는다. */
public final class SharedPriceClient {
    /** Both past and future server clock drift are bounded; observations still cannot exceed servedAt. */
    public static final Duration MAX_SERVER_CLOCK_SKEW = Duration.ofSeconds(5);
    private final URI baseUrl;
    private final Duration timeout;
    private final int maxResponseBytes;
    private final SharedCatalogSnapshot snapshot;
    private final Clock clock;
    private final SharedPriceFreshness freshness;
    private final String apiToken;
    private final HttpClient http;
    private final JsonMapper mapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();

    public SharedPriceClient(URI baseUrl, Duration timeout, int maxResponseBytes,
                             SharedCatalogSnapshot snapshot, Clock clock, SharedPriceFreshness freshness) {
        this(baseUrl, timeout, maxResponseBytes, snapshot, clock, freshness, "");
    }

    public SharedPriceClient(URI baseUrl, Duration timeout, int maxResponseBytes,
                             SharedCatalogSnapshot snapshot, Clock clock, SharedPriceFreshness freshness,
                             String apiToken) {
        this.baseUrl = validateBaseUrl(baseUrl);
        this.apiToken = validateApiToken(apiToken, this.baseUrl);
        if (timeout == null || timeout.compareTo(Duration.ofMillis(100)) < 0
                || timeout.compareTo(Duration.ofSeconds(10)) > 0)
            throw new IllegalArgumentException("Shared price timeout must be 100..10000 milliseconds");
        if (maxResponseBytes < 1024 || maxResponseBytes > 262144)
            throw new IllegalArgumentException("Shared price response limit must be 1024..262144 bytes");
        this.timeout = timeout;
        this.maxResponseBytes = maxResponseBytes;
        this.snapshot = Objects.requireNonNull(snapshot);
        this.clock = Objects.requireNonNull(clock);
        this.freshness = Objects.requireNonNull(freshness);
        this.http = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    private static String validateApiToken(String token, URI origin) {
        if (token == null || token.isEmpty()) {
            if ("https".equals(origin.getScheme()))
                throw new IllegalArgumentException("HTTPS shared prices require a team API token");
            return "";
        }
        if (!token.matches("[0-9a-fA-F]{64}"))
            throw new IllegalArgumentException("Shared price API token must be a 64-character hexadecimal value");
        return token;
    }

    public static URI validateBaseUrl(URI uri) {
        if (uri == null || !uri.isAbsolute() || uri.getHost() == null || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null
                || uri.getPort() == 0 || uri.getPort() > 65535
                || !(uri.getRawPath().isEmpty() || uri.getRawPath().equals("/")))
            throw new IllegalArgumentException("Shared price base URL must be an origin without credentials, path, query or fragment");
        String host = uri.getHost();
        boolean loopback = host.equals("[::1]") || host.equals("::1") || host.equals("[0:0:0:0:0:0:0:1]");
        if (host.matches("127\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}")) {
            loopback = java.util.Arrays.stream(host.split("\\.")).allMatch(part -> Integer.parseInt(part) <= 255);
        }
        if (!"https".equals(uri.getScheme()) && !("http".equals(uri.getScheme()) && loopback))
            throw new IllegalArgumentException("Shared prices require HTTPS or literal loopback HTTP");
        return URI.create(uri.getScheme() + "://" + uri.getRawAuthority());
    }

    public SharedPriceDtos.Envelope fetch(Set<String> canonicalIds) {
        var requested = new LinkedHashSet<>(Objects.requireNonNull(canonicalIds));
        if (requested.isEmpty() || requested.size() > batchSize() || !snapshot.products().keySet().containsAll(requested))
            throw new IllegalArgumentException("Only approved shared canonical IDs may be requested");
        for (String id : requested) {
            if (id == null || !UUID.fromString(id).toString().equals(id))
                throw new IllegalArgumentException("Canonical ID must be an exact UUID");
        }
        var builder = HttpRequest.newBuilder(baseUrl.resolve(snapshot.apiPath() + "?canonicalIds="
                        + requested.stream().sorted().collect(java.util.stream.Collectors.joining(","))))
                .timeout(timeout).header("Accept", "application/json");
        if (snapshot.isFull()) builder.header("X-Catalog-Version", snapshot.version());
        if (!apiToken.isEmpty()) builder.header("Authorization", "Bearer " + apiToken);
        var request = builder.GET().build();
        var requestStarted = clock.instant();
        try {
            // 전체 본문 수신까지 deadline을 둔다. 헤더만 먼저 보내고 본문을 지연시키는 서버도 종료한다.
            var pending = http.sendAsync(request, info -> new BoundedBody(maxResponseBytes));
            HttpResponse<byte[]> response;
            try { response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS); }
            catch (InterruptedException ex) { pending.cancel(true); throw ex; }
            catch (java.util.concurrent.TimeoutException ex) {
                pending.cancel(true);
                throw new IllegalStateException("Shared price lookup timed out");
            }
            if (response.statusCode() != 200) throw new IllegalArgumentException("Shared price HTTP response is unavailable");
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            if (!contentType.split(";", 2)[0].strip().equalsIgnoreCase("application/json"))
                throw new IllegalArgumentException("Shared price response must be JSON");
            SharedPriceDtos.Envelope result;
            try {
                if (snapshot.isFull()) {
                    var full = mapper.treeToValue(mapper.readTree(response.body()), SharedPriceDtos.EnvelopeV2.class);
                    if (full.priceVersion() == null || !full.priceVersion().matches("prices-v1-[0-9a-f]{64}"))
                        throw new IllegalArgumentException("Invalid shared price revision");
                    result = full.toEnvelope();
                } else result = mapper.treeToValue(mapper.readTree(response.body()), SharedPriceDtos.Envelope.class);
            } catch (RuntimeException ex) {
                // JSON 오류의 원문/원인은 헤더를 반사한 서버의 토큰을 포함할 수 있다.
                throw new IllegalArgumentException("Shared price response does not match the approved JSON contract");
            }
            validate(result, requested, requestStarted);
            return result;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Shared price lookup interrupted");
        } catch (java.util.concurrent.ExecutionException ex) {
            throw new IllegalStateException("Shared price lookup failed");
        }
    }

    private void validate(SharedPriceDtos.Envelope response, Set<String> requested, java.time.Instant requestStarted) {
        var now = clock.instant();
        if (response != null && !snapshot.version().equals(response.catalogVersion()))
            throw new IllegalArgumentException("Shared price catalog identity revision does not match");
        if (response != null && (response.servedAt() == null || response.servedAt().isAfter(now.plus(MAX_SERVER_CLOCK_SKEW))
                || response.servedAt().isBefore(requestStarted.minus(MAX_SERVER_CLOCK_SKEW))))
            throw new IllegalArgumentException("Shared price server timestamp is invalid");
        if (response == null || response.schemaVersion() != snapshot.schemaVersion()
                || !freshness.toView().equals(response.policy()) || response.items().size() != requested.size())
            throw new IllegalArgumentException("Shared price envelope does not match the approved contract");
        var received = new HashSet<String>();
        for (var item : response.items()) {
            if (item == null || !requested.contains(item.canonicalId()) || !received.add(item.canonicalId())
                    || item.status() == null || item.freshness() == null)
                throw new IllegalArgumentException("Shared price response IDs must exactly match the request");
            var expected = snapshot.products().get(item.canonicalId());
            if (item.status() == SharedPriceDtos.Status.UNKNOWN_PRODUCT) {
                if (item.product() != null || item.price() != null || item.freshness() != SharedPriceDtos.Freshness.NO_PRICE)
                    throw new IllegalArgumentException("Unknown shared product must not carry identity or price");
                continue;
            }
            if (!expected.identity().equals(item.product()))
                throw new IllegalArgumentException("Shared product identity or sale unit does not match approval");
            if (item.status() == SharedPriceDtos.Status.NO_PRICE) {
                if ((!snapshot.isFull() && expected.price() != null) || item.price() != null
                        || item.freshness() != SharedPriceDtos.Freshness.NO_PRICE)
                    throw new IllegalArgumentException("NO_PRICE is only valid for an approved unpriced product");
                continue;
            }
            if (item.status() != SharedPriceDtos.Status.OK || item.price() == null || item.price().amountKrw() <= 0
                    || item.price().amountKrw() > 999999999999L
                    || (!snapshot.isFull() && !item.price().equals(expected.price()))
                    || (snapshot.isFull() && !SharedCatalogSnapshot.validPriceSource(item.price().sourceName(), item.price().sourceUrl()))
                    || item.price().observedAt() == null || item.price().observedAt().isBefore(java.time.Instant.EPOCH)
                    || item.price().observedAt().isAfter(response.servedAt())
                    || freshness.classify(item.price().observedAt(), response.servedAt()) != item.freshness())
                throw new IllegalArgumentException("Shared price does not match its approved observation");
        }
    }

    /** Bound each HTTP body while allowing later observations without changing the local catalog. */
    public int batchSize() { return snapshot.isFull() ? 50 : 14; }

    /** 제한을 초과하거나 끝나지 않는 본문도 HttpRequest timeout 범위에서 종료한다. */
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;
        BoundedBody(int limit) { this.limit = limit; }
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        @Override public void onNext(List<ByteBuffer> chunks) {
            try {
                for (var chunk : chunks) {
                    if ((long) bytes.size() + chunk.remaining() > limit)
                        throw new IllegalArgumentException("Shared price response exceeds its size limit");
                    byte[] next = new byte[chunk.remaining()]; chunk.get(next); bytes.writeBytes(next);
                }
                subscription.request(1);
            } catch (RuntimeException ex) { subscription.cancel(); result.completeExceptionally(ex); }
        }
        @Override public void onError(Throwable error) { result.completeExceptionally(error); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
