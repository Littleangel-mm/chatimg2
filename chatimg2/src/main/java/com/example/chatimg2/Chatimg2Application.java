package com.example.chatimg2;

import com.example.chatimg2.config.DatabaseMigration;
import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(basePackages = {"com.example.chatimg2", "com.example.chatseedance2"})
@EntityScan(basePackages = {"com.example.chatimg2.entity", "com.example.chatseedance2.entity"})
@EnableJpaRepositories(basePackages = {
        "com.example.chatimg2.repository",
        "com.example.chatseedance2.repository"
})
@EnableAsync
@EnableScheduling
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
