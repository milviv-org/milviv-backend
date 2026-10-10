-- V4: Create plans table
-- План — место работы во времени: на конкретную дату (plan_date)
-- или повторяющийся по дням недели (days_of_week, 1 = понедельник … 7 = воскресенье).
-- Ровно одно из двух должно быть задано.
CREATE TABLE IF NOT EXISTS plans (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    task_id UUID REFERENCES tasks(id) ON DELETE CASCADE, -- план может быть и без задачи
    title VARCHAR(255) NOT NULL,
    description TEXT,
    plan_date DATE,
    days_of_week SMALLINT[],
    time_from TIME NOT NULL,
    time_to TIME NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CHECK (time_to > time_from),
    CHECK ((plan_date IS NOT NULL) <> (days_of_week IS NOT NULL)),
    CHECK (days_of_week IS NULL OR (cardinality(days_of_week) > 0 AND days_of_week <@ ARRAY[1, 2, 3, 4, 5, 6, 7]::SMALLINT[]))
);

-- Indexes
CREATE INDEX idx_plans_user_date ON plans(user_id, plan_date);
CREATE INDEX idx_plans_user_recurring ON plans(user_id) WHERE days_of_week IS NOT NULL;
CREATE INDEX idx_plans_task_id ON plans(task_id);
