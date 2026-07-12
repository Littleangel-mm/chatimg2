package com.example.chatimg2;

import com.example.chatimg2.config.DatabaseMigration;
import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableAsync
@EnableScheduling
public class Chatimg2Application {

    public static void main(String[] args) {
        enableSystemNetworkSettings();
        loadEnv();
        DatabaseMigration.run();
        SpringApplication.run(Chatimg2Application.class, args);
    }

    private static void enableSystemNetworkSettings() {
        System.setProperty("java.net.useSystemProxies", "true");
        System.setProperty("java.net.preferIPv4Stack", "true");
    }

    private static void loadEnv() {
        try {
            Dotenv dotenv = Dotenv.configure()
                    .directory("src/env")
                    .ignoreIfMissing()
                    .ignoreIfMalformed()
                    .load();
            dotenv.entries().forEach(entry -> {
                if (System.getenv(entry.getKey()) == null) {
                    System.setProperty(entry.getKey(), entry.getValue());
                }
            });
        } catch (Exception e) {
            System.err.println("[loadEnv] 加载 .env 失败: " + e.getMessage());
        }
    }
}
