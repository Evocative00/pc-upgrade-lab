package com.pcupgradelab.catalog.price;

import jakarta.persistence.EntityManager;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.Supplier;
import org.hibernate.FlushMode;
import org.hibernate.Session;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 신규 가격의 TIMESTAMP만 Hibernate UTC 바인딩과 MySQL 세션 UTC를 일치시킨다.
 * 전역/개인 설정과 레거시 행은 변경하지 않는다. 호출자는 JPA 트랜잭션 안에 있어야 한다.
 * 같은 Session의 doReturningWork 안에서 쿼리/명시적 flush를 끝내고 풀 반환 전 원래 세션을 복원한다.
 */
@Component
public class CatalogPriceDatabaseTimeZone {
    private final EntityManager entityManager;

    public CatalogPriceDatabaseTimeZone(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public <T> T read(Supplier<T> work) {
        return inUtc(work, false);
    }

    public <T> T write(Supplier<T> work) {
        return inUtc(work, true);
    }

    private <T> T inUtc(Supplier<T> work, boolean writing) {
        if (work == null || !TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Price timezone scope requires work and an active JPA transaction");
        }
        Session session = entityManager.unwrap(Session.class);
        return session.doReturningWork(connection -> {
            // H2의 가격 테스트는 기존 UTC 동작을 유지한다. DB session 설정은 MySQL에만 적용한다.
            if (!"MySQL".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName())) {
                return work.get();
            }
            String originalTimeZone = currentTimeZone(connection);
            FlushMode originalFlushMode = session.getHibernateFlushMode();
            if (writing) {
                // 상위 트랜잭션에 이미 있던 변경을 원래 시간대로 flush한 뒤 가격 범위에 들어간다.
                // importer 자체는 기존 제품/출처를 수정하지 않는다.
                session.flush();
            } else {
                // readOnly가 상위 쓰기 트랜잭션에 합류해도 기존 변경을 UTC 상태에서 자동 flush하지 않는다.
                session.setHibernateFlushMode(FlushMode.MANUAL);
            }
            Throwable failure = null;
            boolean changed = false;
            try {
                if (!"+00:00".equals(originalTimeZone)) {
                    // SET 응답이 실패해도 서버에서는 적용됐을 수 있으므로 복원을 먼저 예약한다.
                    changed = true;
                    setTimeZone(connection, "+00:00");
                }
                T result = work.get();
                // commit은 scope 밖에서 일어난다. 미뤄진 가격 INSERT도 UTC 상태에서 모두 보낸다.
                if (writing) session.flush();
                return result;
            } catch (SQLException | RuntimeException | Error ex) {
                failure = ex;
                throw ex;
            } finally {
                try {
                    if (changed) {
                        try {
                            setTimeZone(connection, originalTimeZone);
                        } catch (SQLException restoreFailure) {
                            // Hikari는 임의 session 변수를 자동 reset하지 않는다. 복원 못 한 연결은 재사용하지 않는다.
                            try {
                                connection.abort(Runnable::run);
                            } catch (SQLException | RuntimeException abortFailure) {
                                restoreFailure.addSuppressed(abortFailure);
                            }
                            if (failure != null) failure.addSuppressed(restoreFailure);
                            else throw new SQLException("Unable to restore price connection timezone; connection aborted",
                                    "08006", restoreFailure);
                        }
                    }
                } finally {
                    session.setHibernateFlushMode(originalFlushMode);
                }
            }
        });
    }

    private static String currentTimeZone(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT @@session.time_zone");
             var result = statement.executeQuery()) {
            if (!result.next() || result.getString(1) == null) {
                throw new SQLException("Unable to read MySQL session timezone");
            }
            return result.getString(1);
        }
    }

    private static void setTimeZone(Connection connection, String timeZone) throws SQLException {
        try (var statement = connection.prepareStatement("SET SESSION time_zone = ?")) {
            statement.setString(1, timeZone);
            statement.execute();
        }
    }
}
