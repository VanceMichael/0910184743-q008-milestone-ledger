package com.example.milestone.config;

import java.util.LinkedHashSet;
import java.util.Set;

/** 运行配置，全部来自环境变量，Compose 负责注入。 */
public record AppConfig(
        String jdbcUrl,
        String dbUser,
        String dbPassword,
        int port,
        Set<String> adminSubjects,
        long timestampSkewSeconds) {

    public static AppConfig fromEnv() {
        return new AppConfig(
                env("DB_JDBC_URL", "jdbc:postgresql://localhost:5432/milestone"),
                env("DB_USER", "milestone"),
                env("DB_PASSWORD", "milestone"),
                Integer.parseInt(env("PORT", "8080")),
                admins(env("LEDGER_ADMIN_SUBJECTS", "admin")),
                Long.parseLong(env("LEDGER_TIMESTAMP_SKEW_SECONDS", "300")));
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }

    private static Set<String> admins(String csv) {
        var out = new LinkedHashSet<String>();
        for (String s : csv.split(",")) {
            if (!s.isBlank()) out.add(s.trim());
        }
        return Set.copyOf(out);
    }
}
