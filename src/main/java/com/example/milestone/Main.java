package com.example.milestone;

import com.example.milestone.db.Database;
import com.example.milestone.db.Migrator;
import com.example.milestone.fault.FaultInjector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;

/** 应用入口：配置 -> 数据源 -> 迁移 -> HTTP 服务。 */
public final class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    private Main() {}

    public static void main(String[] args) throws Exception {
        var config = Config.fromEnv();
        var ds = Database.create(config);
        new Migrator(ds).migrate();

        var app = AppFactory.create(ds, new FaultInjector(), config.port());
        app.start();
        log.info("milestone-ledger 已启动，端口 {}", app.port());

        var stop = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            app.close();
            ds.close();
            stop.countDown();
        }));
        stop.await();
    }
}
