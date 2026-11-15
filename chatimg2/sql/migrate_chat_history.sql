-- Preserve conversation deletions across devices and prevent duplicate active rows.
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
WHERE conversation.id = duplicate.id
  AND duplicate.row_number > 1;

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
  );

CREATE UNIQUE INDEX IF NOT EXISTS ux_chat_conversations_user_client_active
    ON chat_conversations(user_id, client_id)
    WHERE deleted_at IS NULL AND client_id IS NOT NULL;
