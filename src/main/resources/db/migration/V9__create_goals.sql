-- V9: Create goals table
-- Цель связана с проектами и задачами. При удалении цели они остаются без цели (G-15).
CREATE TABLE IF NOT EXISTS goals (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title VARCHAR(255) NOT NULL,
    description TEXT,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE projects ADD COLUMN goal_id UUID REFERENCES goals(id) ON DELETE SET NULL;
ALTER TABLE tasks ADD COLUMN goal_id UUID REFERENCES goals(id) ON DELETE SET NULL;

-- Indexes
CREATE INDEX idx_goals_user_id ON goals(user_id);
CREATE INDEX idx_projects_goal_id ON projects(goal_id);
CREATE INDEX idx_tasks_goal_id ON tasks(goal_id);
