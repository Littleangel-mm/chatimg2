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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

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
            Path workingDirectory = Path.of(System.getProperty("user.dir", "."))
                    .toAbsolutePath()
                    .normalize();
            Set<Path> candidates = new LinkedHashSet<>();
            for (Path current = workingDirectory; current != null; current = current.getParent()) {
                candidates.add(current.resolve("src/env"));
                candidates.add(current.resolve("chatimg2/src/env"));
                candidates.add(current.resolve("chatimg2/chatimg2/src/env"));
            }

            for (Path directory : candidates) {
                if (!Files.isRegularFile(directory.resolve(".env"))) {
                    continue;
                }
                Dotenv dotenv = Dotenv.configure()
                        .directory(directory.toString())
                        .ignoreIfMissing()
                        .ignoreIfMalformed()
                        .load();
                dotenv.entries().forEach(entry -> {
                    if (System.getenv(entry.getKey()) == null) {
                        System.setProperty(entry.getKey(), entry.getValue());
                    }
                });
                System.out.println("[loadEnv] 已加载配置: " + directory.resolve(".env"));
                return;
            }
            System.err.println("[loadEnv] 未找到 .env，数据库将使用环境变量或默认配置");
        } catch (Exception e) {
            System.err.println("[loadEnv] 加载 .env 失败: " + e.getMessage());
        }
    }
}
