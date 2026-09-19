package com.example.milestone.db;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.Function;

/** 事务边界助手：业务函数内所有写操作共享同一连接，整体提交或整体回滚。 */
public final class Tx {

    private Tx() {}

    @FunctionalInterface
    public interface SqlFunction<T> {
        T apply(Connection conn) throws SQLException;
    }

    public static <T> T in(DataSource ds, SqlFunction<T> fn) {
        try (var conn = ds.getConnection()) {
            conn.setAutoCommit(false);
            try {
                T result = fn.apply(conn);
                conn.commit();
                return result;
            } catch (Throwable t) {
                conn.rollback();
                throw t;
            }
        } catch (SQLException e) {
            throw new DbException(e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new DbException(new SQLException(t));
        }
    }

    /** 数据库访问失败的非受检包装。 */
    public static final class DbException extends RuntimeException {
        public DbException(SQLException cause) {
            super(cause);
        }
    }
}
