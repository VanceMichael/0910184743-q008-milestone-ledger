package com.example.milestone.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;

/**
 * 极简迁移器：应用启动时执行 classpath:db/migration/migrations.txt 中列出的脚本，
 * 已应用的版本记录在 schema_migration 表，重复启动为幂等操作。
 * 迁移期间持有 PostgreSQL 咨询锁，多副本同时启动也不会并发执行。
 */
public final class Migrator {

    private static final Logger log = LoggerFactory.getLogger(Migrator.class);
    private static final long MIGRATION_LOCK_ID = 72727272L;

    private final DataSource ds;

    public Migrator(DataSource ds) {
        this.ds = ds;
    }

    public void migrate() {
        try (var conn = ds.getConnection()) {
            conn.setAutoCommit(false);
            try (var st = conn.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS schema_migration ("
                        + "version TEXT PRIMARY KEY, applied_at TIMESTAMPTZ NOT NULL DEFAULT now())");
                // 会话级咨询锁，防止多个应用实例并发迁移
                st.execute("SELECT pg_advisory_lock(" + MIGRATION_LOCK_ID + ")");
            }
            conn.commit();
            try {
                for (String version : pendingScripts(conn)) {
                    log.info("applying migration {}", version);
                    String sql = readResource("db/migration/" + version);
                    try (var st = conn.createStatement()) {
                        st.execute(sql); // pgjdbc 支持一次执行多语句脚本
                        st.executeUpdate("INSERT INTO schema_migration(version) VALUES ('" + version + "')");
                    }
                    conn.commit();
                }
            } finally {
                try {
                    conn.rollback(); // 清理可能的失败事务，再释放咨询锁
                    try (var st = conn.createStatement()) {
                        st.execute("SELECT pg_advisory_unlock(" + MIGRATION_LOCK_ID + ")");
                    }
                    conn.commit();
                } catch (Exception ignored) {
                    // 连接关闭时锁也会释放
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("数据库迁移失败", e);
        }
    }

    private List<String> pendingScripts(Connection conn) throws Exception {
        var applied = new java.util.HashSet<String>();
        try (var st = conn.createStatement();
             var rs = st.executeQuery("SELECT version FROM schema_migration")) {
            while (rs.next()) applied.add(rs.getString(1));
        }
        var pending = new ArrayList<String>();
        for (String line : readResource("db/migration/migrations.txt").split("\\R")) {
            String v = line.trim();
            if (!v.isEmpty() && !applied.contains(v)) pending.add(v);
        }
        return pending;
    }

    private static String readResource(String path) {
        try (var in = Migrator.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("缺少迁移资源 " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
