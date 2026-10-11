package com.pcupgradelab.catalog.shared;

import com.pcupgradelab.catalog.RamSpecRepository;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** opt-in 중앙 읽기 설정. 기본 실행에서는 네트워크 연결과 승인 스냅샷 적재도 하지 않는다. */
@Configuration(proxyBeanMethods = false)
public class SharedPriceAdapterConfig {
    @Bean("sharedPriceClock")
    @ConditionalOnMissingBean(name = "sharedPriceClock")
    Clock sharedPriceClock() { return Clock.systemUTC(); }

    @Bean
    SharedPriceAdapter sharedPriceAdapter(
            @Value("${catalog.shared-prices.enabled:false}") boolean enabled,
            @Value("${catalog.shared-prices.base-url:}") String baseUrl,
            @Value("${catalog.shared-prices.api-token:${CATALOG_SHARED_PRICES_API_TOKEN:}}") String apiToken,
            @Value("${catalog.shared-prices.timeout-ms:2000}") long timeoutMs,
            @Value("${catalog.shared-prices.max-response-bytes:65536}") int maxResponseBytes,
            @Value("${catalog.shared-prices.stale-after-seconds:172800}") long staleSeconds,
            @Value("${catalog.shared-prices.expire-after-seconds:604800}") long expireSeconds,
            @Qualifier("sharedPriceClock") Clock clock, RamSpecRepository ram) {
        if (!enabled) return SharedPriceAdapter.disabled();
        var freshness = new SharedPriceFreshness(Duration.ofSeconds(staleSeconds), Duration.ofSeconds(expireSeconds));
        if (baseUrl.isBlank() || !baseUrl.equals(baseUrl.strip()))
            throw new IllegalArgumentException("Enabled shared prices require an explicit base URL");
        var snapshot = SharedCatalogSnapshot.loadActive();
        var client = new SharedPriceClient(URI.create(baseUrl), Duration.ofMillis(timeoutMs), maxResponseBytes,
                snapshot, clock, freshness, apiToken);
        return new SharedPriceAdapter(snapshot, client, clock, freshness, ram,
                referenceAdapter(snapshot, URI.create(baseUrl), Duration.ofMillis(timeoutMs), maxResponseBytes,
                        clock, apiToken));
    }

    /** Unpublished preview data never enables a production reference request or becomes a fallback price. */
    SharedReferencePriceAdapter referenceAdapter(SharedCatalogSnapshot snapshot, URI baseUrl, Duration timeout,
                                               int maxResponseBytes, Clock clock, String apiToken) {
        try {
            return referenceAdapter(snapshot, baseUrl, timeout, maxResponseBytes, clock, apiToken,
                    SharedReferencePriceSnapshot.loadBundled(snapshot));
        } catch (RuntimeException ex) {
            // An invalid/missing reference publication cannot disable the existing approved current-price service.
            return SharedReferencePriceAdapter.disabled();
        }
    }

    SharedReferencePriceAdapter referenceAdapter(SharedCatalogSnapshot snapshot, URI baseUrl, Duration timeout,
                                               int maxResponseBytes, Clock clock, String apiToken,
                                               SharedReferencePriceDtos.Snapshot references) {
        if (!references.publicationApproved()) return SharedReferencePriceAdapter.disabled();
        return new SharedReferencePriceAdapter(snapshot, new SharedReferencePriceClient(baseUrl, timeout,
                maxResponseBytes, snapshot, clock, apiToken));
    }
}
