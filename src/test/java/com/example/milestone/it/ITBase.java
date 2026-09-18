package com.example.milestone.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.milestone.App;
import com.example.milestone.config.AppConfig;
import com.example.milestone.service.TestHooks;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

/** IT 基类：每类一个真实 PG 实例 + 进程内应用；客户端密钥跨重启复用。 */
abstract class ITBase {
    protected static PgHarness pg;
    protected static App app;
    protected static LedgerClient admin;
    protected static LedgerClient alice;   // PROJECT_PARTY
    protected static LedgerClient bob;     // RIGHTS_HOLDER
    protected static LedgerClient carol;   // VERIFIER
    protected static LedgerClient dave;    // AUDITOR
    private static boolean bootstrapped;

    @BeforeAll
    static void startStack() {
        TestHooks.enableForTests();
        pg = PgHarness.start();
        bootstrapped = false; // 每个 IT 类独立数据库实例，需重新自举
        String base = "http://localhost:0";
        admin = LedgerClient.fresh("admin", base);
        alice = LedgerClient.fresh("alice", base);
        bob = LedgerClient.fresh("bob", base);
        carol = LedgerClient.fresh("carol", base);
        dave = LedgerClient.fresh("dave", base);
        startApp();
    }

    protected static void startApp() {
        if (app != null) app.close();
        app = new App(new AppConfig(pg.jdbcUrl(), pg.user, pg.password, 0, Set.of("admin"), 300));
        app.start();
        String base = "http://localhost:" + app.port();
        for (LedgerClient c : new LedgerClient[] {admin, alice, bob, carol, dave}) {
            c.setBaseUrl(base);
        }
        if (!bootstrapped) {
            var resp = admin.bootstrapCert();
            assertEquals(201, resp.status(), "管理员证书自登记失败: " + resp.body());
            for (LedgerClient c : new LedgerClient[] {alice, bob, carol, dave}) {
                var r = admin.registerCert(c.subject, c.publicKeyB64());
                assertEquals(201, r.status(), "证书登记失败 " + c.subject + ": " + r.body());
            }
            bootstrapped = true;
        }
    }

    @AfterAll
    static void stopStack() {
        if (app != null) app.close();
        if (pg != null) pg.close();
    }

    /** 标准项目：一个里程碑 M1，需两类证据、两方审批。 */
    protected record Fixture(String projectId, String versionId, String milestoneId) {}

    protected Fixture newStandardProject() {
        var milestone = LedgerClient.milestoneDef("M1",
                new String[] {"IP_ASSIGNMENT", "PROTOTYPE_DEMO"},
                new String[] {"RIGHTS_HOLDER", "VERIFIER"},
                "300000.00");
        var resp = admin.createProject("成果转化项目-" + System.nanoTime(), milestone,
                LedgerClient.permission("alice", "PROJECT_PARTY"),
                LedgerClient.permission("bob", "RIGHTS_HOLDER"),
                LedgerClient.permission("carol", "VERIFIER"),
                LedgerClient.permission("dave", "AUDITOR"));
        assertEquals(201, resp.status(), "项目创建失败: " + resp.body());
        String projectId = resp.body().get("projectId").asText();
        String versionId = resp.body().get("versionId").asText();
        String milestoneId = resp.body().get("milestones").get(0).get("id").asText();
        return new Fixture(projectId, versionId, milestoneId);
    }

    protected void submitAllEvidence(Fixture f) {
        var r1 = alice.submitEvidence(f.projectId(), f.versionId(), f.milestoneId(),
                "IP_ASSIGNMENT", "ev-ip-" + System.nanoTime());
        assertEquals(201, r1.status(), "证据提交失败: " + r1.body());
        var r2 = carol.submitEvidence(f.projectId(), f.versionId(), f.milestoneId(),
                "PROTOTYPE_DEMO", "ev-demo-" + System.nanoTime());
        assertEquals(201, r2.status(), "证据提交失败: " + r2.body());
    }

    // ---- SQL 断言辅助 ----

    protected long sqlLong(String sql, Object... params) {
        try (var conn = DriverManager.getConnection(pg.jdbcUrl(), pg.user, pg.password);
                var ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
            var rs = ps.executeQuery();
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    protected String sqlString(String sql, Object... params) {
        try (var conn = DriverManager.getConnection(pg.jdbcUrl(), pg.user, pg.password);
                var ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
            var rs = ps.executeQuery();
            assertTrue(rs.next(), "查询无结果: " + sql);
            return rs.getString(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    protected long disbursementCount(String projectId) {
        return sqlLong("SELECT COUNT(*) FROM disbursements WHERE project_id = ?::uuid", projectId);
    }

    protected long maxSeq(String projectId) {
        return sqlLong("SELECT COALESCE(MAX(seq), 0) FROM evidence_events WHERE project_id = ?::uuid", projectId);
    }
}
