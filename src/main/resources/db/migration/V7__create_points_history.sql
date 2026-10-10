-- V7: Create points history table
-- Источник правды для баланса (AD-16): баланс = SUM(amount) по пользователю.
-- Записи только добавляются. Проекты баллов не дают.
CREATE TABLE IF NOT EXISTS points_history (
    id BIGSERIAL PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    kind VARCHAR(20) NOT NULL CHECK (kind IN ('TASK_COMPLETED', 'REWARD_REDEEMED')),
    amount INTEGER NOT NULL, -- начисление > 0, списание < 0
    reason VARCHAR(255) NOT NULL, -- "Task completed: Learn Java", "Reward redeemed: New game"
    task_id UUID REFERENCES tasks(id) ON DELETE SET NULL,
    reward_id UUID, -- FK добавляется в V10 вместе с таблицей rewards
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CHECK ((kind = 'TASK_COMPLETED' AND amount > 0) OR (kind = 'REWARD_REDEEMED' AND amount < 0))
);

-- Одна задача — одно начисление (QG-03)
CREATE UNIQUE INDEX uq_points_history_task_completed ON points_history(task_id) WHERE kind = 'TASK_COMPLETED';

-- Indexes
CREATE INDEX idx_points_history_user_id ON points_history(user_id);
CREATE INDEX idx_points_history_created_at ON points_history(created_at DESC);
