-- V2: Create tasks table
-- Опоздание не является статусом: задача просрочена, если status = 'ACTIVE' и deadline < сегодня.
CREATE TABLE IF NOT EXISTS tasks (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title VARCHAR(255) NOT NULL,
    description TEXT,
    difficulty VARCHAR(20) NOT NULL CHECK (difficulty IN ('NOVICE', 'APPRENTICE', 'ADEPT', 'EXPERT', 'MASTER', 'LEGENDARY')),
    points INTEGER NOT NULL CHECK (points BETWEEN 1 AND 1000), -- по умолчанию приложение берёт баллы по сложности
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'COMPLETED', 'CANCELLED')),
    start_date DATE,
    deadline DATE,
    reminder_at TIMESTAMP,
    completed_at TIMESTAMP,
    project_id UUID,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CHECK (start_date IS NULL OR deadline IS NULL OR start_date <= deadline)
);

-- Indexes for better performance
CREATE INDEX idx_tasks_user_id ON tasks(user_id);
CREATE INDEX idx_tasks_status ON tasks(status);
CREATE INDEX idx_tasks_deadline ON tasks(deadline);
CREATE INDEX idx_tasks_project_id ON tasks(project_id);
CREATE INDEX idx_tasks_created_at ON tasks(created_at DESC);
