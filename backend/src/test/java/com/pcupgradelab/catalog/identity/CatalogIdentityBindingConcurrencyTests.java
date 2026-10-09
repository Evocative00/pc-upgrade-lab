package com.pcupgradelab.catalog.identity;

import com.pcupgradelab.catalog.*;
import com.pcupgradelab.pc.PartType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.*;

/** 각 쓰레드의 독립 트랜잭션을 실제로 경쟁시킨다. 전용 H2이며 MySQL 통합 검사를 대신하지 않는다. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:catalog-binding-concurrency;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000",
        "spring.datasource.hikari.maximum-pool-size=4", "catalog.seed.enabled=false"
})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CatalogIdentityBindingConcurrencyTests {
    @Autowired CatalogIdentityService identity;
    @Autowired CatalogProductService products;
    @Autowired CatalogProductRepository productRepository;
    @Autowired CatalogProductSourceRepository productSources;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcTemplate jdbc;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void competingBindingReadsTheCommittedWinnerAndCannotReplaceIt(boolean preloadCompetingProduct) throws Exception {
        var transactions = new TransactionTemplate(transactionManager);
        var fixture = transactions.execute(status -> {
            String productId = products.create(new CatalogProductCreateRequest(PartType.CPU, "Fixture", "Concurrent CPU", "FIXTURE-CPU")).id();
            var product = productRepository.findById(productId).orElseThrow();
            var source = productSources.saveAndFlush(new CatalogProductSource(product, new CatalogSourceInput(
                    CatalogSourceName.MANUFACTURER, null, null, "https://example.com/concurrent-product",
                    Map.of("record_kind", "TEST_IDENTITY_EXTRACT"), Instant.parse("2026-10-09T01:00:00Z"))));
            var firstModel = model("First reviewed CPU model");
            var secondModel = model("Conflicting CPU model");
            return new Fixture(productId, source.getId(), firstModel.id(), secondModel.id());
        });
        var winner = new CatalogIdentityRequests.ProductBinding(fixture.productId(), UUID.randomUUID().toString(), fixture.firstModelId(),
                CatalogIdentityKind.PHYSICAL_VARIANT, CatalogRole.BOTH, fixture.sourceId(), "First approved product identity");
        var contender = new CatalogIdentityRequests.ProductBinding(fixture.productId(), UUID.randomUUID().toString(), fixture.secondModelId(),
                CatalogIdentityKind.MODEL_REFERENCE, CatalogRole.INSTALLED_PC_REFERENCE, fixture.sourceId(), "Conflicting later identity");
        var firstBound = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var secondPreloaded = new CountDownLatch(preloadCompetingProduct ? 1 : 0);
        var allowSecondBinding = new CountDownLatch(preloadCompetingProduct ? 1 : 0);
        var secondSession = new AtomicInteger();
        var executor = Executors.newFixedThreadPool(2);
        try {
            // 캐시 사례에서는 두 번째 트랜잭션이 미분류 행을 먼저 읽어 둔다.
            var second = preloadCompetingProduct ? executor.submit(() -> transactions.execute(status -> {
                secondSession.set(jdbc.queryForObject("SELECT SESSION_ID()", Integer.class));
                assertThat(productRepository.findById(fixture.productId()).orElseThrow().getCanonicalId()).isNull();
                secondPreloaded.countDown();
                await(allowSecondBinding);
                return identity.bindProduct(contender);
            })) : null;
            await(secondPreloaded);
            var first = executor.submit(() -> transactions.execute(status -> {
                var result = identity.bindProduct(winner);
                firstBound.countDown();
                await(releaseFirst);
                return result;
            }));
            await(firstBound);
            if (preloadCompetingProduct) allowSecondBinding.countDown();
            else second = executor.submit(() -> transactions.execute(status -> {
                secondSession.set(jdbc.queryForObject("SELECT SESSION_ID()", Integer.class));
                return identity.bindProduct(contender);
            }));
            waitForDatabaseLock(secondSession);
            assertThat(second.isDone()).isFalse();
            releaseFirst.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS).canonicalId()).isEqualTo(winner.canonicalId());
            var competingResult = second;
            assertThatThrownBy(() -> competingResult.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(IllegalArgumentException.class)
                    .hasRootCauseMessage("Existing canonical ID must not be replaced");
            var after = products.findById(fixture.productId()).orElseThrow();
            assertThat(after.id()).isEqualTo(fixture.productId());
            assertThat(after.canonicalId()).isEqualTo(winner.canonicalId());
            assertThat(after.modelId()).isEqualTo(winner.modelId());
            assertThat(after.identityKind()).isEqualTo(winner.identityKind());
            assertThat(after.role()).isEqualTo(winner.role());
            assertThat(jdbc.queryForObject("SELECT identity_review_scope FROM catalog_product WHERE id = ?", String.class, fixture.productId()))
                    .isEqualTo(winner.reviewScope());
        } finally {
            allowSecondBinding.countDown(); releaseFirst.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void waitForDatabaseLock(AtomicInteger sessionId) throws InterruptedException {
        // https://h2database.com/html/systemtables.html#sessions 의 BLOCKER_ID로 실제 DB 잠금 대기를 확인한다.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (sessionId.get() != 0 && jdbc.queryForObject("""
                    SELECT COUNT(*) FROM INFORMATION_SCHEMA.SESSIONS
                    WHERE SESSION_ID = ? AND BLOCKER_ID > 0
                    """, Integer.class, sessionId.get()) == 1) return;
            TimeUnit.MILLISECONDS.sleep(20);
        }
        fail("The competing transaction never entered a database lock wait");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("Concurrent transaction coordination timed out");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt(); throw new AssertionError("Concurrent transaction interrupted", ex);
        }
    }

    private CatalogIdentityDtos.Model model(String name) {
        var source = new CatalogSourceInput(CatalogSourceName.MANUFACTURER, null, null, "https://example.com/concurrent-model",
                Map.of("record_kind", "TEST_MODEL_EXTRACT"), Instant.parse("2026-10-09T01:00:00Z"));
        return identity.registerModel(new CatalogIdentityRequests.ModelRegistration(UUID.randomUUID().toString(), PartType.CPU,
                "Fixture", name, CatalogModelKind.CPU_MODEL, CatalogRole.INSTALLED_PC_REFERENCE, null, null,
                List.of(new CatalogIdentityRequests.ModelSource(source, "Fixture model identity")), List.of()));
    }

    private record Fixture(String productId, long sourceId, String firstModelId, String secondModelId) { }
}
