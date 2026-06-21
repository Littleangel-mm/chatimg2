package com.example.chatimg2.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public final class DatabaseMigration {

    private static final Logger log = LoggerFactory.getLogger(DatabaseMigration.class);

    private DatabaseMigration() {
    }

    public static void run() {
        String host = env("DB_HOST", "47.108.211.186");
        String port = env("DB_PORT", "5432");
        String dbName = env("DB_NAME", "chatimg2");
        String user = env("DB_USER", "root");
        String password = env("DB_PASSWORD", "fl3692458121");
        String url = "jdbc:postgresql://" + host + ":" + port + "/" + dbName;

        try (Connection conn = DriverManager.getConnection(url, user, password);
             Statement stmt = conn.createStatement()) {
            stmt.execute("ALTER TABLE generation_records ADD COLUMN IF NOT EXISTS status VARCHAR(20) DEFAULT 'processing'");
            stmt.execute("ALTER TABLE generation_records ADD COLUMN IF NOT EXISTS error_message TEXT");
            stmt.execute("""
                    UPDATE generation_records
                    SET status = 'completed'
                    WHERE image_url IS NOT NULL AND (status IS NULL OR status = 'processing')
                    """);
            stmt.execute("""
                    UPDATE generation_records
                    SET status = 'processing'
                    WHERE image_url IS NULL AND (status IS NULL OR status = '')
                    """);
            stmt.execute("ALTER TABLE generation_records ADD COLUMN IF NOT EXISTS task_code VARCHAR(32)");
            stmt.execute("""
                    UPDATE generation_records
                    SET task_code = 'TASK-LEGACY-' || LPAD(id::text, 8, '0')
                    WHERE task_code IS NULL
                    """);
            stmt.execute("ALTER TABLE generation_records ALTER COLUMN task_code SET NOT NULL");
            stmt.execute("""
                    CREATE UNIQUE INDEX IF NOT EXISTS idx_generation_records_task_code
                    ON generation_records(task_code)
                    """);
            stmt.execute("""
                    UPDATE generation_records
                    SET image_url = regexp_replace(image_url, '^.*/', '')
                    WHERE image_url IS NOT NULL AND image_url LIKE '%/%'
                    """);
            log.info("Database migration completed (generation_records.status, error_message, task_code, image_path)");
        } catch (SQLException e) {
            throw new IllegalStateException("Database migration failed: " + e.getMessage(), e);
        }
    }

    private static String env(String key, String defaultValue) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            value = System.getProperty(key);
        }
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
