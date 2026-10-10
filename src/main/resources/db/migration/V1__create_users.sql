-- V1: Create users table
-- id генерирует приложение (UUID), как в User / UserJdbcRepository.
-- Баланс баллов здесь не хранится: источник правды — points_history (AD-16).
CREATE TABLE IF NOT EXISTS users (
    id UUID PRIMARY KEY,
    username VARCHAR(100) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    email VARCHAR(255),
    hidden_rewards_password_hash VARCHAR(255), -- один пароль на все скрытые награды (G-18), BCrypt
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Index for faster username lookups
CREATE INDEX idx_users_username ON users(username);
