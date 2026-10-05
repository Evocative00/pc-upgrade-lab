package com.pcupgradelab.auth;

import java.sql.SQLException;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.springframework.stereotype.Component;

/** V11의 첫 DDL 전에 기존 개발용 회원 ID를 확인한다. 데이터 소유권은 자동 변경하지 않는다. */
@Component
public class PcOwnerMigrationPreflight implements Callback {
    private static final MigrationVersion USER_TABLE_VERSION = MigrationVersion.fromVersion("11");

    @Override
    public boolean supports(Event event, Context context) {
        return event == Event.BEFORE_EACH_MIGRATE && context.getMigrationInfo() != null
                && USER_TABLE_VERSION.equals(context.getMigrationInfo().getVersion());
    }

    @Override
    public boolean canHandleInTransaction(Event event, Context context) {
        return true;
    }

    @Override
    public void handle(Event event, Context context) {
        try (var statement = context.getConnection().prepareStatement(
                "SELECT COUNT(*) FROM pc_configuration WHERE user_id IS NOT NULL");
             var result = statement.executeQuery()) {
            result.next();
            long assignedPcCount = result.getLong(1);
            if (assignedPcCount > 0) {
                throw new FlywayException("V11 preflight refused: " + assignedPcCount
                        + " PC configuration(s) have an existing non-NULL user_id. "
                        + "Back up the database and review these development owners with the PM before migrating. "
                        + "Do not auto-assign or delete them. See docs/week2-google-login-setup.md.");
            }
        } catch (SQLException exception) {
            throw new FlywayException("V11 PC owner preflight could not read existing owners; migration stopped.", exception);
        }
    }

    @Override
    public String getCallbackName() {
        return "pc-owner-v11-preflight";
    }
}
