package com.example.milestone.db;

import com.example.milestone.Config;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;

/** HikariCP 连接池。 */
public final class Database {

    private Database() {}

    public static HikariDataSource create(Config config) {
        var hc = new HikariConfig();
        hc.setJdbcUrl(config.jdbcUrl());
        hc.setUsername(config.jdbcUser());
        hc.setPassword(config.jdbcPassword());
        hc.setMaximumPoolSize(16);
        hc.setMinimumIdle(2);
        hc.setConnectionTimeout(10_000);
        hc.setPoolName("ledger-pool");
        return new HikariDataSource(hc);
    }

    /** 健康检查：真实执行 SELECT 1。 */
    public static boolean healthy(DataSource ds) {
        try (var c = ds.getConnection(); var st = c.createStatement(); var rs = st.executeQuery("SELECT 1")) {
            return rs.next();
        } catch (Exception e) {
            return false;
        }
    }
}
