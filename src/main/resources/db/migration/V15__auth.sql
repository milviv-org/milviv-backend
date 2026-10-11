-- V15: Authentication — вход по email, refresh-токены

-- Вход по email: email обязателен и уникален (хранится в нижнем регистре),
-- username больше не логин, а отображаемое имя
DROP INDEX IF EXISTS idx_users_username;
ALTER TABLE users DROP CONSTRAINT IF EXISTS users_username_key;
ALTER TABLE users RENAME COLUMN username TO name;
ALTER TABLE users ALTER COLUMN email SET NOT NULL;
ALTER TABLE users ADD CONSTRAINT uq_users_email UNIQUE (email);

-- Refresh-токены сессий. Хранится только SHA-256 хеш токена.
-- Токены одной сессии объединены family_id: ротация создаёт новый токен в той же семье,
-- повторное использование заменённого токена отзывает всю семью.
CREATE TABLE IF NOT EXISTS refresh_tokens (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    family_id UUID NOT NULL,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    session_expires_at TIMESTAMP NOT NULL,
    revoked_at TIMESTAMP
);

CREATE INDEX idx_refresh_tokens_family_id ON refresh_tokens(family_id);
CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens(user_id);
