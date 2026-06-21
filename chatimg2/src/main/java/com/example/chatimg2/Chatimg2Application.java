package com.example.chatimg2;

import com.example.chatimg2.config.DatabaseMigration;
import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class Chatimg2Application {

    public static void main(String[] args) {
        loadEnv();
        DatabaseMigration.run();
        SpringApplication.run(Chatimg2Application.class, args);
    }

    private static void loadEnv() {
        try {
            Dotenv dotenv = Dotenv.configure()
                    .directory("src/env")
                    .ignoreIfMissing()
                    .load();
            dotenv.entries().forEach(entry -> {
                if (System.getenv(entry.getKey()) == null) {
                    System.setProperty(entry.getKey(), entry.getValue());
                }
            });
        } catch (Exception ignored) {
        }
    }
}
