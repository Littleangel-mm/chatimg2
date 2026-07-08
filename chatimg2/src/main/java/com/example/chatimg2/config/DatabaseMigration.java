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
            stmt.execute("ALTER TABLE generation_records ADD COLUMN IF NOT EXISTS user_id INTEGER");
            stmt.execute("ALTER TABLE generation_records ADD COLUMN IF NOT EXISTS user_email VARCHAR(120)");
            stmt.execute("UPDATE generation_records SET status = 'completed' WHERE image_url IS NOT NULL AND (status IS NULL OR status = 'processing')");
            stmt.execute("UPDATE generation_records SET status = 'processing' WHERE image_url IS NULL AND (status IS NULL OR status = '')");
            stmt.execute("ALTER TABLE generation_records ADD COLUMN IF NOT EXISTS task_code VARCHAR(32)");
            stmt.execute("UPDATE generation_records SET task_code = 'TASK-LEGACY-' || LPAD(id::text, 8, '0') WHERE task_code IS NULL");
            stmt.execute("ALTER TABLE generation_records ALTER COLUMN task_code SET NOT NULL");
            stmt.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_generation_records_task_code ON generation_records(task_code)");
            stmt.execute("UPDATE generation_records SET image_url = regexp_replace(image_url, '^.*/', '') WHERE image_url IS NOT NULL AND image_url LIKE '%/%'");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_generation_records_user_id ON generation_records(user_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_generation_records_user_email ON generation_records(user_email)");
            stmt.execute("ALTER TABLE generation_records ALTER COLUMN key_id DROP NOT NULL");

            stmt.execute("ALTER TABLE activation_keys ADD COLUMN IF NOT EXISTS user_id INTEGER");
            stmt.execute("ALTER TABLE activation_keys ADD COLUMN IF NOT EXISTS user_email VARCHAR(120)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_activation_keys_user_id ON activation_keys(user_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_activation_keys_user_email ON activation_keys(user_email)");

            stmt.execute("CREATE TABLE IF NOT EXISTS inspiration_prompts (" +
                    "id SERIAL PRIMARY KEY, " +
                    "external_id VARCHAR(64) UNIQUE, " +
                    "media_type VARCHAR(16) NOT NULL DEFAULT 'image', " +
                    "category VARCHAR(100), " +
                    "subcategory VARCHAR(100), " +
                    "image_url VARCHAR(1000), " +
                    "source_url VARCHAR(1000), " +
                    "prompt TEXT NOT NULL, " +
                    "sort_order INTEGER DEFAULT 0, " +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                    "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                    ")");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_inspiration_media_type ON inspiration_prompts(media_type)");

            stmt.execute("CREATE TABLE IF NOT EXISTS users (" +
                    "id SERIAL PRIMARY KEY, " +
                    "email VARCHAR(120) NOT NULL UNIQUE, " +
                    "username VARCHAR(80), " +
                    "password_hash VARCHAR(255), " +
                    "role VARCHAR(20) NOT NULL DEFAULT 'USER', " +
                    "status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE', " +
                    "total_credits INTEGER NOT NULL DEFAULT 100, " +
                    "used_credits INTEGER NOT NULL DEFAULT 0, " +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                    "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                    "last_login_at TIMESTAMP" +
                    ")");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_users_email ON users(email)");

            stmt.execute("CREATE TABLE IF NOT EXISTS auth_sessions (" +
                    "id SERIAL PRIMARY KEY, " +
                    "token_hash VARCHAR(128) NOT NULL UNIQUE, " +
                    "user_id INTEGER REFERENCES users(id) ON DELETE CASCADE, " +
                    "admin_id INTEGER REFERENCES admins(id) ON DELETE CASCADE, " +
                    "role VARCHAR(20) NOT NULL, " +
                    "expires_at TIMESTAMP NOT NULL, " +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                    ")");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_auth_sessions_token_hash ON auth_sessions(token_hash)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_auth_sessions_user_id ON auth_sessions(user_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_auth_sessions_admin_id ON auth_sessions(admin_id)");

            stmt.execute("CREATE TABLE IF NOT EXISTS email_verification_codes (" +
                    "id SERIAL PRIMARY KEY, " +
                    "email VARCHAR(120) NOT NULL, " +
                    "scene VARCHAR(20) NOT NULL, " +
                    "code_hash VARCHAR(128) NOT NULL, " +
                    "used BOOLEAN NOT NULL DEFAULT FALSE, " +
                    "attempts INTEGER NOT NULL DEFAULT 0, " +
                    "expires_at TIMESTAMP NOT NULL, " +
                    "used_at TIMESTAMP, " +
                    "request_ip VARCHAR(64), " +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                    ")");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_email_codes_email_scene ON email_verification_codes(email, scene, used, created_at DESC)");

            log.info("Database migration completed (auth, generation_records.*, activation_keys.*, inspiration_prompts)");
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
