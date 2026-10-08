package com.pcupgradelab.catalog.price;

import jakarta.persistence.EntityManager;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import org.hibernate.FlushMode;
import org.hibernate.Session;
import org.hibernate.jdbc.ReturningWork;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 연결 설정의 복원과 실패 경로를 모의 검증한다. 실제 MySQL의 절대 시각 검증은 별도로 수행한다. */
class CatalogPriceDatabaseTimeZoneTests {
    private final EntityManager entityManager = mock(EntityManager.class);
    private final Session session = mock(Session.class);
    private final Connection connection = mock(Connection.class);
    private final DatabaseMetaData metadata = mock(DatabaseMetaData.class);
    private final PreparedStatement select = mock(PreparedStatement.class);
    private final PreparedStatement set = mock(PreparedStatement.class);
    private final ResultSet result = mock(ResultSet.class);
    private final AtomicReference<String> zone = new AtomicReference<>("SYSTEM");
    private final AtomicReference<FlushMode> flushMode = new AtomicReference<>(FlushMode.AUTO);
    private final List<String> events = new ArrayList<>();
    private final CatalogPriceDatabaseTimeZone guard = new CatalogPriceDatabaseTimeZone(entityManager);

    @BeforeEach
    void setup() throws Exception {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        when(entityManager.unwrap(Session.class)).thenReturn(session);
        when(session.doReturningWork(any())).thenAnswer(invocation ->
                ((ReturningWork<?>) invocation.getArgument(0)).execute(connection));
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("MySQL");
        when(connection.prepareStatement("SELECT @@session.time_zone")).thenReturn(select);
        when(select.executeQuery()).thenReturn(result);
        when(result.next()).thenReturn(true);
        when(result.getString(1)).thenAnswer(invocation -> zone.get());
        when(connection.prepareStatement("SET SESSION time_zone = ?")).thenReturn(set);
        doAnswer(invocation -> {
            zone.set(invocation.getArgument(1));
            events.add("zone:" + zone.get());
            return null;
        }).when(set).setString(eq(1), anyString());
        when(session.getHibernateFlushMode()).thenAnswer(invocation -> flushMode.get());
        doAnswer(invocation -> {
            flushMode.set(invocation.getArgument(0));
            return null;
        }).when(session).setHibernateFlushMode(any());
        doAnswer(invocation -> {
            events.add("flush:" + zone.get());
            return null;
        }).when(session).flush();
    }

    @AfterEach
    void clearTransactionMarker() {
        TransactionSynchronizationManager.clear();
    }

    @Test
    void h2ExecutesWorkWithoutChangingConnectionOrFlushMode() throws Exception {
        when(metadata.getDatabaseProductName()).thenReturn("H2");
        assertThat(guard.read(() -> 123)).isEqualTo(123);
        assertThat(guard.write(() -> 456)).isEqualTo(456);
        verify(connection, never()).prepareStatement(anyString());
        verify(session, never()).flush();
        verify(session, never()).setHibernateFlushMode(any());
    }

    @Test
    void outsideATransactionIsRefusedBeforeObtainingAConnection() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
        assertThatThrownBy(() -> guard.read(() -> 1)).isInstanceOf(IllegalStateException.class);
        verify(entityManager, never()).unwrap(any());
    }

    @Test
    void readUsesUtcAndManualFlushThenRestoresOriginalSettings() throws Exception {
        assertThat(guard.read(() -> {
            assertThat(zone.get()).isEqualTo("+00:00");
            assertThat(flushMode.get()).isEqualTo(FlushMode.MANUAL);
            return 123;
        })).isEqualTo(123);
        assertThat(zone.get()).isEqualTo("SYSTEM");
        assertThat(flushMode.get()).isEqualTo(FlushMode.AUTO);
        verify(session, never()).flush();
    }

    @Test
    void writeFlushesExistingChangesBeforeUtcAndPriceChangesBeforeRestore() {
        guard.write(() -> {
            events.add("work:" + zone.get());
            return 123;
        });
        assertThat(events).containsExactly("flush:SYSTEM", "zone:+00:00", "work:+00:00", "flush:+00:00", "zone:SYSTEM");
        assertThat(flushMode.get()).isEqualTo(FlushMode.AUTO);
    }

    @Test
    void aWorkFailureRestoresZoneAndFlushModeWithoutFlushingPartialWork() throws Exception {
        var failure = new IllegalArgumentException("price failure");
        assertThatThrownBy(() -> guard.read(() -> { throw failure; })).isSameAs(failure);
        assertThat(zone.get()).isEqualTo("SYSTEM");
        assertThat(flushMode.get()).isEqualTo(FlushMode.AUTO);
        verify(session, never()).flush();
    }

    @Test
    void finalFlushFailureStillRestoresTheSession() {
        doAnswer(invocation -> {
            if ("+00:00".equals(zone.get())) throw new IllegalStateException("flush failure");
            return null;
        }).when(session).flush();
        assertThatThrownBy(() -> guard.write(() -> 123)).isInstanceOf(IllegalStateException.class)
                .hasMessage("flush failure");
        assertThat(zone.get()).isEqualTo("SYSTEM");
        assertThat(flushMode.get()).isEqualTo(FlushMode.AUTO);
    }

    @Test
    void aFailedSetAttemptAlsoRestoresBecauseTheServerMayHaveAppliedIt() throws Exception {
        when(set.execute()).thenAnswer(invocation -> {
            if ("+00:00".equals(zone.get())) throw new SQLException("SET response failed");
            return true;
        });
        assertThatThrownBy(() -> guard.read(() -> 123)).isInstanceOf(SQLException.class);
        assertThat(zone.get()).isEqualTo("SYSTEM");
        assertThat(flushMode.get()).isEqualTo(FlushMode.AUTO);
    }

    @Test
    void restorationFailureAbortsTheConnectionAndPropagatesAFailure() throws Exception {
        when(set.execute()).thenAnswer(invocation -> {
            if ("SYSTEM".equals(zone.get())) throw new SQLException("restore failed");
            return true;
        });
        assertThatThrownBy(() -> guard.read(() -> 123)).isInstanceOf(SQLException.class)
                .hasMessageContaining("connection aborted");
        verify(connection).abort(any(Executor.class));
        assertThat(flushMode.get()).isEqualTo(FlushMode.AUTO);
    }
}
