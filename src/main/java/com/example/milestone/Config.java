package com.example.milestone;

/** 运行配置，全部来自环境变量（Compose 注入）。 */
public record Config(String jdbcUrl, String jdbcUser, String jdbcPassword, int port) {

    public static Config fromEnv() {
        return new Config(
                env("DATABASE_URL", "jdbc:postgresql://localhost:5432/milestone"),
                env("DB_USER", "milestone"),
                env("DB_PASSWORD", "milestone"),
                Integer.parseInt(env("PORT", "8080")));
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return (v == null || v.isBlank()) ? fallback : v;
    }
}
