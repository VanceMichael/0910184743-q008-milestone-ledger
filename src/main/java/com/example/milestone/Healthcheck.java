package com.example.milestone;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Compose 健康检查：java -cp app.jar com.example.milestone.Healthcheck，退出码 0/1。 */
public final class Healthcheck {
    private Healthcheck() {}

    public static void main(String[] args) {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        try {
            var resp = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/health"))
                            .timeout(Duration.ofSeconds(3))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            System.exit(resp.statusCode() == 200 && resp.body().contains("\"ok\"") ? 0 : 1);
        } catch (Exception e) {
            System.exit(1);
        }
    }
}
