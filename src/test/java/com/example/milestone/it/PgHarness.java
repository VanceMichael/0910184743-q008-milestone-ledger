package com.example.milestone.it;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 集成测试数据库：默认拉起真实 PostgreSQL 16 二进制（zonky embedded，无需 Docker）；
 * 设置 IT_JDBC_URL/IT_DB_USER/IT_DB_PASSWORD 时改用外部库（如 Compose 中的 postgres:16）。
 * 外部模式不支持重启，恢复性测试用 Assumptions 跳过。
 */
final class PgHarness implements AutoCloseable {
    final String user;
    final String password;
    private final boolean external;
    private final Path dataDir;
    private EmbeddedPostgres pg;
    private String jdbcUrl;

    private PgHarness(boolean external, Path dataDir, String user, String password) {
        this.external = external;
        this.dataDir = dataDir;
        this.user = user;
        this.password = password;
    }

    static PgHarness start() {
        String externalUrl = System.getenv("IT_JDBC_URL");
        if (externalUrl != null && !externalUrl.isBlank()) {
            var h = new PgHarness(true, null,
                    System.getenv().getOrDefault("IT_DB_USER", "milestone"),
                    System.getenv().getOrDefault("IT_DB_PASSWORD", "milestone"));
            h.jdbcUrl = externalUrl;
            return h;
        }
        try {
            Path dir = Files.createTempDirectory("milestone-pg-");
            var h = new PgHarness(false, dir, "postgres", "");
            h.pg = EmbeddedPostgres.builder()
                    .setDataDirectory(dir.toFile())
                    .setCleanDataDirectory(false)
                    .start();
            h.jdbcUrl = h.pg.getJdbcUrl("postgres", "postgres");
            return h;
        } catch (IOException e) {
            throw new IllegalStateException("嵌入式 PostgreSQL 启动失败", e);
        }
    }

    String jdbcUrl() {
        return jdbcUrl;
    }

    boolean canRestart() {
        return !external;
    }

    /** 停库再用同一数据目录拉起：验证审计游标与历史证书跨重启存活。 */
    void restart() {
        if (external) throw new UnsupportedOperationException("外部数据库不支持重启");
        try {
            pg.close();
            pg = EmbeddedPostgres.builder()
                    .setDataDirectory(dataDir.toFile())
                    .setCleanDataDirectory(false)
                    .start();
            jdbcUrl = pg.getJdbcUrl("postgres", "postgres");
        } catch (IOException e) {
            throw new IllegalStateException("数据库重启失败", e);
        }
    }

    @Override
    public void close() {
        if (pg != null) {
            try {
                pg.close();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
