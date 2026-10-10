-- V10: Create rewards table
-- Скрытые награды защищены общим паролем пользователя (users.hidden_rewards_password_hash).
CREATE TABLE IF NOT EXISTS rewards (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title VARCHAR(255) NOT NULL,
    description TEXT,
    cost INTEGER NOT NULL CHECK (cost > 0),
    hidden BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Получение награды списывает баллы записью в points_history
ALTER TABLE points_history ADD CONSTRAINT fk_points_history_reward
    FOREIGN KEY (reward_id) REFERENCES rewards(id) ON DELETE SET NULL;

-- Indexes
CREATE INDEX idx_rewards_user_id ON rewards(user_id);
CREATE INDEX idx_points_history_reward_id ON points_history(reward_id);
