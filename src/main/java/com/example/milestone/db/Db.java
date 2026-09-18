package com.example.milestone.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.postgresql.util.PGobject;

/** 数据源与事务边界。所有写路径共用一个事务提交，是本类存在的意义。 */
public final class Db implements AutoCloseable {
    private final HikariDataSource ds;

    public Db(String jdbcUrl, String user, String password) {
        var cfg = new HikariConfig();
        cfg.setJdbcUrl(jdbcUrl);
        cfg.setUsername(user);
        cfg.setPassword(password);
        cfg.setMaximumPoolSize(16);
        cfg.setConnectionTimeout(10_000);
        cfg.setPoolName("milestone-ledger");
        this.ds = new HikariDataSource(cfg);
    }

    public DataSource dataSource() {
        return ds;
    }

    @FunctionalInterface
    public interface SqlFunction<A, R> {
        R apply(A a) throws SQLException;
    }

    /** 只读访问，自动提交。 */
    public <T> T read(SqlFunction<Connection, T> fn) {
        try (var c = ds.getConnection()) {
            return fn.apply(c);
        } catch (SQLException e) {
            throw new DataAccessException(e);
        }
    }

    /**
     * 单事务执行：证据事件、里程碑投影、放款指令在同一 commit 中落库；
     * 任何异常（含故障注入）整体回滚，重试由幂等键保证安全。
     */
    public <T> T tx(SqlFunction<Connection, T> fn) {
        try (var c = ds.getConnection()) {
            c.setAutoCommit(false);
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            T result;
            try {
                result = fn.apply(c);
            } catch (Throwable t) {
                try {
                    c.rollback();
                } catch (SQLException ignore) {
                    // 连接已断开时回滚失败无需掩盖原始异常
                }
                if (t instanceof RuntimeException re) throw re;
                if (t instanceof SQLException se) throw new DataAccessException(se);
                throw new RuntimeException(t);
            }
            try {
                c.commit();
            } catch (SQLException e) {
                try {
                    c.rollback();
                } catch (SQLException ignore) {
                    // 同上
                }
                throw new DataAccessException(e);
            }
            return result;
        } catch (SQLException e) {
            throw new DataAccessException(e);
        }
    }

    public static PGobject jsonb(String json) {
        try {
            var o = new PGobject();
            o.setType("jsonb");
            o.setValue(json);
            return o;
        } catch (SQLException e) {
            throw new DataAccessException(e);
        }
    }

    @Override
    public void close() {
        ds.close();
    }
}
