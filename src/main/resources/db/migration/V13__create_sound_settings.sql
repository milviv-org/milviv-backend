-- V13: Create sound settings table

-- Звук пользователя для события, связанного с задачами
CREATE TABLE IF NOT EXISTS user_sound_settings (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    event VARCHAR(50) NOT NULL, -- например TASK_COMPLETED; список событий не определён
    sound VARCHAR(100) NOT NULL,
    PRIMARY KEY (user_id, event)
);
