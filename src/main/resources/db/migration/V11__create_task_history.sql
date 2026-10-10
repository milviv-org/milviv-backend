-- V11: Create task history tables (QG-04)
-- Append-only: записи только добавляются. При удалении задачи история остаётся (task_id → NULL, G-20).

-- Факт выполнения: когда выполнена, сколько баллов, план против факта
CREATE TABLE IF NOT EXISTS task_completions (
    id BIGSERIAL PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    task_id UUID REFERENCES tasks(id) ON DELETE SET NULL,
    difficulty VARCHAR(20) NOT NULL CHECK (difficulty IN ('NOVICE', 'APPRENTICE', 'ADEPT', 'EXPERT', 'MASTER', 'LEGENDARY')),
    points INTEGER NOT NULL CHECK (points BETWEEN 1 AND 1000),
    planned_date DATE, -- на какой день задача была запланирована
    deadline DATE,     -- срок на момент выполнения
    completed_at TIMESTAMP NOT NULL
);

-- История переносов плановой даты задачи
CREATE TABLE IF NOT EXISTS task_reschedules (
    id BIGSERIAL PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    task_id UUID REFERENCES tasks(id) ON DELETE SET NULL,
    from_date DATE NOT NULL,
    to_date DATE NOT NULL,
    rescheduled_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Indexes
CREATE INDEX idx_task_completions_user_completed ON task_completions(user_id, completed_at DESC);
CREATE INDEX idx_task_completions_task_id ON task_completions(task_id);
CREATE INDEX idx_task_reschedules_user_id ON task_reschedules(user_id);
CREATE INDEX idx_task_reschedules_task_id ON task_reschedules(task_id);
