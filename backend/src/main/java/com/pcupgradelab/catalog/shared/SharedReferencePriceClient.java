package com.pcupgradelab.catalog.shared;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
import static com.pcupgradelab.catalog.shared.SharedReferencePriceValidation.require;

/** Bounded independent reference reader. It never turns a historical estimate into a current quote. */
public final class SharedReferencePriceClient {
    public static final String API_PATH = "/api/v1/reference-prices";
    private final URI baseUrl;
    private final Duration timeout;
    private final int maxResponseBytes;
    private final SharedCatalogSnapshot catalog;
    private final Clock clock;
    private final String apiToken;
    private final HttpClient http;
    private final JsonMapper mapper = strictMapper();

    static JsonMapper strictMapper() {
        return JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    }

    public SharedReferencePriceClient(URI baseUrl, Duration timeout, int maxResponseBytes,
                                     SharedCatalogSnapshot catalog, Clock clock, String apiToken) {
        this.baseUrl = SharedPriceClient.validateBaseUrl(baseUrl);
        require(timeout != null && timeout.compareTo(Duration.ofMillis(100)) >= 0
                && timeout.compareTo(Duration.ofSeconds(10)) <= 0, "Reference timeout must be 100..10000 milliseconds");
        require(maxResponseBytes >= 1024 && maxResponseBytes <= 262144, "Reference body limit is invalid");
        String token = apiToken == null ? "" : apiToken;
        require(token.isEmpty() ? !"https".equals(this.baseUrl.getScheme()) : token.matches("[0-9a-fA-F]{64}"),
                "HTTPS reference prices require a valid team token");
        this.timeout = timeout; this.maxResponseBytes = maxResponseBytes;
        this.catalog = Objects.requireNonNull(catalog); this.clock = Objects.requireNonNull(clock);
        this.apiToken = token;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public int batchSize() { return 20; }

    public SharedReferencePriceDtos.Envelope fetch(Set<String> canonicalIds) {
        var requested = new LinkedHashSet<>(Objects.requireNonNull(canonicalIds));
        require(!requested.isEmpty() && requested.size() <= batchSize()
                && catalog.products().keySet().containsAll(requested), "Only approved reference identities may be requested");
        for (var id : requested) require(id != null && UUID.fromString(id).toString().equals(id), "Reference ID is invalid");
        var builder = HttpRequest.newBuilder(baseUrl.resolve(API_PATH + "?canonicalIds="
                        + requested.stream().sorted().collect(java.util.stream.Collectors.joining(","))))
                .timeout(timeout).header("Accept", "application/json").header("X-Catalog-Version", catalog.version());
        if (!apiToken.isEmpty()) builder.header("Authorization", "Bearer " + apiToken);
        var startedAt = clock.instant();
        try {
            var pending = http.sendAsync(builder.GET().build(), info -> new BoundedBody(maxResponseBytes));
            HttpResponse<byte[]> response;
            try { response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS); }
            catch (InterruptedException ex) { pending.cancel(true); throw ex; }
            catch (java.util.concurrent.TimeoutException ex) {
                pending.cancel(true); throw new IllegalStateException("Reference price lookup timed out");
            }
            require(response.statusCode() == 200, "Reference price HTTP response is unavailable");
            require(response.headers().firstValue("Content-Type").orElse("").split(";", 2)[0].strip()
                    .equalsIgnoreCase("application/json"), "Reference response must be JSON");
            SharedReferencePriceDtos.Envelope result;
            try { result = mapper.treeToValue(mapper.readTree(response.body()), SharedReferencePriceDtos.Envelope.class); }
            catch (RuntimeException ex) { throw new IllegalArgumentException("Reference response does not match the JSON contract"); }
            validate(result, requested, startedAt);
            return result;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("Reference lookup interrupted");
        } catch (java.util.concurrent.ExecutionException ex) {
            var failure = ex.getCause();
            while (failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null)
                failure = failure.getCause();
            if (failure instanceof java.net.http.HttpTimeoutException)
                throw new IllegalStateException("Reference price lookup timed out");
            throw new IllegalStateException("Reference lookup failed");
        }
    }

    private void validate(SharedReferencePriceDtos.Envelope response, Set<String> requested, Instant startedAt) {
        var now = clock.instant();
        require(response != null && response.schemaVersion() == 1 && catalog.version().equals(response.catalogVersion())
                && SharedReferencePriceValidation.version(response.referenceVersion()) && response.servedAt() != null
                && !response.servedAt().isAfter(now.plus(SharedPriceClient.MAX_SERVER_CLOCK_SKEW))
                && !response.servedAt().isBefore(startedAt.minus(SharedPriceClient.MAX_SERVER_CLOCK_SKEW))
                && response.items() != null && response.items().size() == requested.size(), "Reference envelope differs");
        var received = new HashSet<String>();
        for (var item : response.items()) {
            require(item != null && requested.contains(item.canonicalId()) && received.add(item.canonicalId())
                    && item.status() != null, "Reference response IDs must exactly match the request");
            var expected = catalog.products().get(item.canonicalId());
            if (item.status() == SharedReferencePriceDtos.Status.UNKNOWN_PRODUCT) {
                require(item.product() == null && item.referenceEstimate() == null, "Unknown reference must not carry a value");
                continue;
            }
            require(expected.identity().equals(item.product()), "Reference product identity differs");
            if (item.status() == SharedReferencePriceDtos.Status.NO_REFERENCE) {
                require(item.referenceEstimate() == null, "Missing reference must not carry a value");
                continue;
            }
            require(item.status() == SharedReferencePriceDtos.Status.OK && expected.price() == null,
                    "Reference extension must preserve existing current prices");
            SharedReferencePriceValidation.validate(item.referenceEstimate(), expected.identity(), catalog, response.servedAt());
        }
    }

    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;
        private final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;
        BoundedBody(int limit) { this.limit = limit; }
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        @Override public void onNext(List<ByteBuffer> chunks) {
            try {
                for (var chunk : chunks) {
                    require((long) bytes.size() + chunk.remaining() <= limit, "Reference response exceeds its size limit");
                    byte[] next = new byte[chunk.remaining()]; chunk.get(next); bytes.writeBytes(next);
                }
                subscription.request(1);
            } catch (RuntimeException ex) { subscription.cancel(); result.completeExceptionally(ex); }
        }
        @Override public void onError(Throwable error) { result.completeExceptionally(error); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
