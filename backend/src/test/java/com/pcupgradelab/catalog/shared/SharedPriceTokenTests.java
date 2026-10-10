package com.pcupgradelab.catalog.shared;

import com.pcupgradelab.catalog.CatalogPriceStatus;
import com.pcupgradelab.catalog.CatalogProductView;
import com.pcupgradelab.catalog.RamSpecRepository;
import com.pcupgradelab.pc.PartType;
import com.sun.net.httpserver.HttpServer;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class SharedPriceTokenTests {
    // 고정된 합성 테스트 값이다. 실제 팀 토큰을 사용하지 않는다.
    private static final String TOKEN = "0123456789abcdef".repeat(4);
    private final SharedPriceFreshness policy = SharedPriceFreshness.defaults();
    private final JsonMapper mapper = JsonMapper.builder().build();
    private SharedCatalogSnapshot snapshot;
    private SharedCatalogSnapshot.Entry priced;
    private Clock clock;

    @BeforeEach void fixture() {
        snapshot = SharedCatalogSnapshot.loadPilot();
        priced = snapshot.products().values().stream()
                .filter(e -> e.identity().type() == PartType.CPU && e.price() != null).findFirst().orElseThrow();
        clock = Clock.fixed(snapshot.products().values().stream().filter(e -> e.price() != null)
                .map(e -> e.price().observedAt()).max(java.util.Comparator.naturalOrder()).orElseThrow()
                .plusSeconds(3600), ZoneOffset.UTC);
    }

    @Test void tokenTravelsOnlyInTheAuthorizationHeaderOfTheApprovedOrigin() throws Exception {
        try (var server = new TokenServer()) {
            server.reply.set(new Reply(200, envelope(), null));
            assertThat(client(server.url, TOKEN).fetch(Set.of(priced.identity().canonicalId())).items()).hasSize(1);
            var request = server.lastRequest.get();
            assertThat(request.authorization()).isEqualTo("Bearer " + TOKEN);
            assertThat(request.uri()).isEqualTo("/api/v1/prices?canonicalIds=" + priced.identity().canonicalId());
            assertThat(request.uri()).doesNotContain(TOKEN);
            assertThat(request.body()).isEmpty();
            assertThat(request.cookie()).isNull();
            assertThat(request.method()).isEqualTo("GET");
        }
    }

    @Test void theLegacyLoopbackClientKeepsWorkingWithoutAnyAuthenticationHeader() throws Exception {
        try (var server = new TokenServer()) {
            server.reply.set(new Reply(200, envelope(), null));
            new SharedPriceClient(server.url, Duration.ofSeconds(2), 65536, snapshot, clock, policy)
                    .fetch(Set.of(priced.identity().canonicalId()));
            assertThat(server.lastRequest.get().authorization()).isNull();
        }
    }

    @Test void httpsRequiresATokenAndBothTransportsRejectMalformedTokensWithoutEchoingThem() {
        var remote = URI.create("https://prices.example.com");
        for (String missing : new String[] { null, "" }) {
            var failure = catchThrowable(() -> client(remote, missing));
            assertThat(failure).isInstanceOf(IllegalArgumentException.class).hasNoCause();
        }
        assertThatThrownBy(() -> new SharedPriceClient(remote, Duration.ofSeconds(2), 65536, snapshot, clock, policy))
                .isInstanceOf(IllegalArgumentException.class).hasNoCause();
        for (String bad : List.of(" " + TOKEN, TOKEN + " ", TOKEN + "\r\n", "z".repeat(64),
                TOKEN.substring(1), TOKEN + "0", "Bearer " + TOKEN)) {
            for (var origin : List.of(remote, URI.create("http://127.0.0.1:8081"))) {
                var failure = catchThrowable(() -> client(origin, bad));
                assertThat(failure).isInstanceOf(IllegalArgumentException.class).hasNoCause();
                assertThat(stackTrace(failure)).doesNotContain(bad).doesNotContain(TOKEN);
            }
        }
        assertThat(client(remote, TOKEN)).isNotNull();
        assertThat(client(remote, TOKEN.toUpperCase(java.util.Locale.ROOT))).isNotNull();
    }

    @Test void disabledConfigurationIgnoresUnusedTokenOriginAndPolicySettings() {
        var config = new SharedPriceAdapterConfig();
        var product = view();
        var disabled = config.sharedPriceAdapter(false, "invalid-origin", "private invalid\r\nvalue",
                -1, -1, -1, -1, clock, mock(RamSpecRepository.class));
        assertThat(disabled.enrich(List.of(product))).containsExactly(product);
        assertThatThrownBy(() -> config.sharedPriceAdapter(true, "https://prices.example.com", "", 2000, 65536,
                172800, 604800, clock, mock(RamSpecRepository.class)))
                .isInstanceOf(IllegalArgumentException.class).hasNoCause();
    }

    @Test void theDocumentedEnvironmentVariableBindsAndAnExplicitPropertyTakesPrecedence() throws Exception {
        for (boolean override : List.of(false, true)) {
            try (var server = new TokenServer(); var context = new AnnotationConfigApplicationContext()) {
                var full = SharedCatalogSnapshot.loadActive();
                server.reply.set(new Reply(200, mapper.writeValueAsString(new SharedPriceDtos.EnvelopeV2(
                        2, full.version(), full.priceVersion(), clock.instant(), policy.toView(),
                        List.of(new SharedPriceDtos.Item(priced.identity().canonicalId(), priced.identity(), priced.price(),
                                SharedPriceDtos.Status.OK, policy.classify(priced.price().observedAt(), clock.instant()))))), null));
                var properties = new HashMap<String, Object>();
                properties.put("catalog.shared-prices.enabled", true);
                properties.put("catalog.shared-prices.base-url", server.url.toString());
                if (override) properties.put("catalog.shared-prices.api-token", "abcdef0123456789".repeat(4));
                // 실제 환경변수/VM 속성에 팀 토큰이 있어도 이 로컬 테스트에는 사용하지 않는다.
                context.getEnvironment().getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                context.getEnvironment().getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
                context.getEnvironment().getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                        "synthetic-token-environment", Map.of("CATALOG_SHARED_PRICES_API_TOKEN", TOKEN)));
                context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("token-test", properties));
                context.getBeanFactory().registerSingleton("sharedPriceClock", clock);
                context.getBeanFactory().registerSingleton("ramSpecRepository", mock(RamSpecRepository.class));
                context.register(SharedPriceAdapterConfig.class);
                context.refresh();
                var enriched = context.getBean(SharedPriceAdapter.class).enrich(List.of(view()), Map.of()).getFirst();
                assertThat(enriched.priceStatus().lookupStatus()).isEqualTo("OK");
                assertThat(server.lastRequest.get().authorization())
                        .isEqualTo("Bearer " + (override ? "abcdef0123456789".repeat(4) : TOKEN));
            }
        }
    }

    @Test void redirectsNeverForwardTheTeamTokenToAnotherOrigin() throws Exception {
        try (var source = new TokenServer(); var destination = new TokenServer()) {
            source.reply.set(new Reply(302, "{}", destination.url.resolve("/redirect").toString()));
            assertThatThrownBy(() -> client(source.url, TOKEN).fetch(Set.of(priced.identity().canonicalId())))
                    .isInstanceOf(IllegalArgumentException.class).hasNoCause();
            assertThat(source.lastRequest.get().authorization()).isEqualTo("Bearer " + TOKEN);
            assertThat(source.requests).hasValue(1);
            assertThat(destination.requests).hasValue(0);
        }
    }

    @Test void authenticationFailureKeepsOnlyTheLastVerifiedPriceAndNeverExposesTheToken() throws Exception {
        try (var server = new TokenServer()) {
            var adapter = new SharedPriceAdapter(snapshot, client(server.url, TOKEN), clock, policy, mock(RamSpecRepository.class));
            var product = view();
            server.reply.set(new Reply(200, envelope(), null));
            var first = adapter.enrich(List.of(product), Map.of()).getFirst();
            assertThat(first.priceStatus().lookupStatus()).isEqualTo("OK");
            server.reply.set(new Reply(401, "{\"error\":\"" + TOKEN + "\"}", null));
            var cached = adapter.enrich(List.of(product), Map.of()).getFirst();
            assertThat(cached.currentPrice()).isEqualTo(priced.price());
            assertThat(cached.priceStatus().lookupStatus()).isEqualTo("UNAVAILABLE");
            assertThat(cached.priceStatus().lastSuccessAt()).isEqualTo(first.priceStatus().lastSuccessAt());
            assertThat(cached.priceStatus().includedInTotal()).isFalse();
            assertThat(mapper.writeValueAsString(cached)).doesNotContain(TOKEN).doesNotContain("Authorization");
            var empty = new SharedPriceAdapter(snapshot, client(server.url, TOKEN), clock, policy, mock(RamSpecRepository.class))
                    .enrich(List.of(product.withPrice(priced.price(), null)), Map.of()).getFirst();
            assertThat(empty.currentPrice()).isNull();
            assertThat(empty.priceStatus().lastSuccessAt()).isNull();
            assertThat(mapper.writeValueAsString(empty)).doesNotContain(TOKEN);
        }
    }

    @Test void aMalformedResponseThatReflectsTheTokenCannotPutItIntoExceptionMessagesOrCauses() throws Exception {
        try (var server = new TokenServer()) {
            for (var reflected : List.of("{\"items\":\"" + TOKEN + "\"}", "{\"schemaVersion\":\"" + TOKEN + "\"}")) {
                server.reply.set(new Reply(200, reflected, null));
                var failure = catchThrowable(() -> client(server.url, TOKEN).fetch(Set.of(priced.identity().canonicalId())));
                assertThat(failure).isInstanceOf(IllegalArgumentException.class).hasNoCause();
                assertThat(stackTrace(failure)).doesNotContain(TOKEN);
            }
        }
    }

    private SharedPriceClient client(URI origin, String token) {
        return new SharedPriceClient(origin, Duration.ofSeconds(2), 65536, snapshot, clock, policy, token);
    }

    private String envelope() {
        return mapper.writeValueAsString(new SharedPriceDtos.Envelope(1, snapshot.version(), clock.instant(), policy.toView(),
                List.of(new SharedPriceDtos.Item(priced.identity().canonicalId(), priced.identity(), priced.price(),
                        SharedPriceDtos.Status.OK, policy.classify(priced.price().observedAt(), clock.instant())))));
    }

    private CatalogProductView view() {
        var p = priced.identity();
        return new CatalogProductView(UUID.randomUUID().toString(), p.type(), p.manufacturer(), p.modelName(), p.partNumber(),
                p.verificationStatus(), p.active(), clock.instant(), clock.instant(),
                new CatalogProductView.ReferencePrice(null, CatalogPriceStatus.UNCONFIRMED, clock.instant()), null,
                p.canonicalId(), null, p.identityKind(), p.role());
    }

    private static String stackTrace(Throwable failure) {
        var text = new StringWriter();
        failure.printStackTrace(new PrintWriter(text));
        return text.toString();
    }

    private record Reply(int status, String body, String location) {}
    private record CapturedRequest(String method, String uri, String authorization, String cookie, String body) {}

    private static final class TokenServer implements AutoCloseable {
        final HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final URI url = URI.create("http://127.0.0.1:" + http.getAddress().getPort());
        final AtomicReference<Reply> reply = new AtomicReference<>(new Reply(503, "{}", null));
        final AtomicReference<CapturedRequest> lastRequest = new AtomicReference<>();
        final AtomicInteger requests = new AtomicInteger();

        TokenServer() throws Exception {
            http.createContext("/", exchange -> {
                requests.incrementAndGet();
                lastRequest.set(new CapturedRequest(exchange.getRequestMethod(), exchange.getRequestURI().toString(),
                        exchange.getRequestHeaders().getFirst("Authorization"), exchange.getRequestHeaders().getFirst("Cookie"),
                        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                var response = reply.get();
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                if (response.location() != null) exchange.getResponseHeaders().set("Location", response.location());
                var bytes = response.body().getBytes(StandardCharsets.UTF_8);
                try {
                    exchange.sendResponseHeaders(response.status(), bytes.length);
                    exchange.getResponseBody().write(bytes);
                } finally { exchange.close(); }
            });
            http.start();
        }

        @Override public void close() { http.stop(0); }
    }
}
