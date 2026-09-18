package com.example.milestone.db;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;

/** 应用启动时执行迁移；Compose 中依赖数据库健康检查通过后才会走到这里。 */
public final class Migrations {
    private Migrations() {}

    public static void migrate(DataSource ds) {
        Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }
}
