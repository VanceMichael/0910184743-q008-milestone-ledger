package com.example.milestone;

import com.example.milestone.config.AppConfig;

public final class Main {
    private Main() {}

    public static void main(String[] args) {
        AppConfig cfg = AppConfig.fromEnv();
        App app = new App(cfg);
        Runtime.getRuntime().addShutdownHook(new Thread(app::close));
        app.start();
        System.out.println("milestone-ledger listening on :" + cfg.port());
    }
}
