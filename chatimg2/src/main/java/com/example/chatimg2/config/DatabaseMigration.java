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
        String host = env("DB_HOST", "localhost");
        String port = env("DB_PORT", "5432");
        String dbName = env("DB_NAME", "chatimg2");
        String user = env("DB_USER", "postgres");
        String password = env("DB_PASSWORD", "");
        String url = "jdbc:postgresql://" + host + ":" + port + "/" + dbName;

        try (Connection conn = DriverManager.getConnection(url, user, password);
             Statement stmt = conn.createStatement()) {
            stmt.execute("ALTER TABLE generation_records ADD COLUMN IF NOT EXISTS status VARCHAR(20) DEFAULT 'processing'");
            stmt.execute("ALTER TABLE generation_records ADD COLUMN IF NOT EXISTS error_message TEXT");
            stmt.execute("ALTER TABLE generation_records ADD COLUMN IF NOT EXISTS user_id INTEGER");
            stmt.execute("ALTER TABLE generation_records ADD COLUMN IF NOT EXISTS user_email VARCHAR(120)");
            stmt.execute("ALTER TABLE generation_records ADD COLUMN IF NOT EXISTS client_message_id VARCHAR(120)");
            stmt.execute("ALTER TABLE generation_records ADD COLUMN IF NOT EXISTS remote_image_url TEXT");
            stmt.execute("UPDATE generation_records SET status = 'completed' WHERE image_url IS NOT NULL AND (status IS NULL OR status = 'processing')");
            stmt.execute("UPDATE generation_records SET status = 'processing' WHERE image_url IS NULL AND (status IS NULL OR status = '')");
            stmt.execute("ALTER TABLE generation_records ADD COLUMN IF NOT EXISTS task_code VARCHAR(32)");
            stmt.execute("UPDATE generation_records SET task_code = 'TASK-LEGACY-' || LPAD(id::text, 8, '0') WHERE task_code IS NULL");
            stmt.execute("ALTER TABLE generation_records ALTER COLUMN task_code SET NOT NULL");
            stmt.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_generation_records_task_code ON generation_records(task_code)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_generation_records_user_id ON generation_records(user_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_generation_records_user_email ON generation_records(user_email)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_generation_records_client_message_id ON generation_records(client_message_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_generation_records_recovery ON generation_records(status, created_at)");
            stmt.execute("ALTER TABLE generation_records ALTER COLUMN key_id DROP NOT NULL");

            stmt.execute("CREATE TABLE IF NOT EXISTS distributed_locks (" +
                    "lock_key VARCHAR(160) PRIMARY KEY, " +
                    "owner_id VARCHAR(160) NOT NULL, " +
                    "expires_at TIMESTAMP NOT NULL, " +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                    "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                    ")");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_distributed_locks_expires_at ON distributed_locks(expires_at)");

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

            stmt.execute("CREATE TABLE IF NOT EXISTS chat_conversations (" +
                    "id SERIAL PRIMARY KEY, " +
                    "key_id INTEGER, " +
                    "user_id INTEGER, " +
                    "user_email VARCHAR(120), " +
                    "client_id VARCHAR(80), " +
                    "title VARCHAR(255) NOT NULL DEFAULT '新对话', " +
                    "conversation_type VARCHAR(20) NOT NULL DEFAULT 'text2img', " +
                    "creative_brief TEXT, " +
                    "final_prompt TEXT, " +
                    "reference_image_path VARCHAR(500), " +
                    "brief_version INTEGER NOT NULL DEFAULT 1, " +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                    "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                    ")");
            stmt.execute("ALTER TABLE chat_conversations ALTER COLUMN key_id DROP NOT NULL");
            stmt.execute("ALTER TABLE chat_conversations ADD COLUMN IF NOT EXISTS user_id INTEGER");
            stmt.execute("ALTER TABLE chat_conversations ADD COLUMN IF NOT EXISTS user_email VARCHAR(120)");
            stmt.execute("ALTER TABLE chat_conversations ADD COLUMN IF NOT EXISTS client_id VARCHAR(80)");
            stmt.execute("ALTER TABLE chat_conversations ADD COLUMN IF NOT EXISTS conversation_type VARCHAR(20) NOT NULL DEFAULT 'text2img'");
            stmt.execute("ALTER TABLE chat_conversations ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_chat_conversations_user_id ON chat_conversations(user_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_chat_conversations_client_id ON chat_conversations(client_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_chat_conversations_active_user ON chat_conversations(user_id, deleted_at, updated_at DESC)");

            stmt.execute("""
                    WITH duplicates AS (
                        SELECT id,
                               ROW_NUMBER() OVER (
                                   PARTITION BY user_id, client_id
                                   ORDER BY updated_at DESC NULLS LAST, id DESC
                               ) AS row_number
                        FROM chat_conversations
                        WHERE deleted_at IS NULL AND client_id IS NOT NULL
                    )
                    UPDATE chat_conversations conversation
                    SET deleted_at = CURRENT_TIMESTAMP,
                        updated_at = CURRENT_TIMESTAMP
                    FROM duplicates duplicate
                    WHERE conversation.id = duplicate.id AND duplicate.row_number > 1
                    """);
            stmt.execute("""
                    UPDATE chat_conversations active
                    SET deleted_at = CURRENT_TIMESTAMP,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE active.deleted_at IS NULL
                      AND active.client_id IS NOT NULL
                      AND EXISTS (
                          SELECT 1
                          FROM chat_conversations deleted
                          WHERE deleted.user_id = active.user_id
                            AND deleted.client_id = active.client_id
                            AND deleted.deleted_at IS NOT NULL
                      )
                    """);
            stmt.execute("CREATE UNIQUE INDEX IF NOT EXISTS ux_chat_conversations_user_client_active "
                    + "ON chat_conversations(user_id, client_id) "
                    + "WHERE deleted_at IS NULL AND client_id IS NOT NULL");

            stmt.execute("CREATE TABLE IF NOT EXISTS chat_messages (" +
                    "id SERIAL PRIMARY KEY, " +
                    "conversation_id INTEGER NOT NULL REFERENCES chat_conversations(id) ON DELETE CASCADE, " +
                    "role VARCHAR(20) NOT NULL, " +
                    "content TEXT, " +
                    "image_path VARCHAR(500), " +
                    "source_image_url TEXT, " +
                    "generation_record_id INTEGER, " +
                    "intent VARCHAR(50), " +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                    ")");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_chat_messages_conversation_id ON chat_messages(conversation_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_chat_messages_created_at ON chat_messages(created_at)");
            stmt.execute("ALTER TABLE chat_messages ADD COLUMN IF NOT EXISTS client_id VARCHAR(120)");
            stmt.execute("ALTER TABLE chat_messages ADD COLUMN IF NOT EXISTS video_task_code VARCHAR(40)");
            stmt.execute("ALTER TABLE chat_messages ADD COLUMN IF NOT EXISTS source_image_url TEXT");
            stmt.execute("ALTER TABLE generation_records ADD COLUMN IF NOT EXISTS source_image_paths TEXT");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_chat_messages_client_id ON chat_messages(conversation_id, client_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_chat_messages_video_task_code ON chat_messages(video_task_code)");

            log.info("Database migration completed (auth, generation_records.*, distributed_locks, activation_keys.*, inspiration_prompts, chat_*)");
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
