package com.example.milestone;

import com.example.milestone.api.Api;
import com.example.milestone.auth.AuthService;
import com.example.milestone.config.AppConfig;
import com.example.milestone.db.Db;
import com.example.milestone.db.Migrations;
import com.example.milestone.http.Server;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;

/** 应用装配：建数据源 → 迁移 → 起 HTTP 服务。集成测试以临时端口在进程内运行。 */
public final class App implements AutoCloseable {
    private final Db db;
    private final HttpServer server;

    public App(AppConfig cfg) {
        this.db = new Db(cfg.jdbcUrl(), cfg.dbUser(), cfg.dbPassword());
        Migrations.migrate(db.dataSource());
        try {
            var auth = new AuthService(db, cfg.adminSubjects(), cfg.timestampSkewSeconds());
            this.server = Server.create(cfg.port(), Api.router(db, cfg), auth);
        } catch (IOException e) {
            throw new IllegalStateException("HTTP 服务启动失败", e);
        }
    }

    public void start() {
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
        db.close();
    }
}
