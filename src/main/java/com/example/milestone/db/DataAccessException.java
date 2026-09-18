package com.example.milestone.db;

/** 包装 SQLException 的非受检异常。 */
public class DataAccessException extends RuntimeException {
    public DataAccessException(Throwable cause) {
        super(cause);
    }
}
