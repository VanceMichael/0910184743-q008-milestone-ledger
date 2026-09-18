package com.example.milestone.it;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

/** 冒烟测试辅助：拉起一个真实 PG 并打印 JDBC URL，供独立 jar 进程使用。 */
public final class SmokePg {
    private SmokePg() {}

    public static void main(String[] args) throws Exception {
        var pg = EmbeddedPostgres.builder().start();
        System.out.println("JDBC_URL=" + pg.getJdbcUrl("postgres", "postgres"));
        System.out.flush();
        Thread.currentThread().join();
    }
}
