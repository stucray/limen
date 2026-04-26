package db.migration;

import com.stucray.limen.security.JdbcSigningKeyStore;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

@SuppressWarnings("checkstyle:TypeName")
public class V12__BackfillTenantSigningKeys extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        String kek = System.getenv("LIMEN_KEY_ENCRYPTION_KEY");
        if (kek == null || kek.isBlank()) {
            throw new IllegalStateException(
                "LIMEN_KEY_ENCRYPTION_KEY is not set; cannot backfill tenant signing keys"
            );
        }
        Connection conn = context.getConnection();
        List<Long> tenantIds = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
            "SELECT t.id FROM tenants t " +
            "WHERE t.slug <> 'system' " +
            "AND NOT EXISTS (SELECT 1 FROM tenant_signing_key k " +
            "                WHERE k.tenant_id = t.id AND k.status = 'ACTIVE')"
        ); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                tenantIds.add(rs.getLong("id"));
            }
        }
        for (Long tenantId : tenantIds) {
            JdbcSigningKeyStore.insertActiveSigningKey(conn, tenantId, kek);
        }
    }
}
