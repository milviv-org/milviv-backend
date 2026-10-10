-- V12: Create reflection tables (FR-12)
-- Вопросы бывают стандартными (user_id IS NULL) и созданными пользователем.
-- Какие вопросы задать после выполнения задачи — task_reflection_questions;
-- нет строк — рефлексия для задачи отключена.

CREATE TABLE IF NOT EXISTS reflection_questions (
    id UUID PRIMARY KEY,
    user_id UUID REFERENCES users(id) ON DELETE CASCADE, -- NULL = стандартный вопрос
    text VARCHAR(500) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS task_reflection_questions (
    task_id UUID NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    question_id UUID NOT NULL REFERENCES reflection_questions(id) ON DELETE CASCADE,
    PRIMARY KEY (task_id, question_id)
);

-- Ответ привязан к факту выполнения, поэтому сохраняется и после удаления задачи (AR-04).
-- Текст вопроса копируется: ответ не теряет смысл, если вопрос изменят или удалят.
CREATE TABLE IF NOT EXISTS reflection_answers (
    id BIGSERIAL PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    completion_id BIGINT NOT NULL REFERENCES task_completions(id) ON DELETE CASCADE,
    question_id UUID REFERENCES reflection_questions(id) ON DELETE SET NULL,
    question_text VARCHAR(500) NOT NULL,
    answer TEXT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Стандартные вопросы (пользовательская документация)
INSERT INTO reflection_questions (id, user_id, text) VALUES
    ('00000000-0000-0000-0000-000000000001', NULL, 'Что помогло выполнить задачу?'),
    ('00000000-0000-0000-0000-000000000002', NULL, 'Что помешало?'),
    ('00000000-0000-0000-0000-000000000003', NULL, 'Что можно было сделать иначе?');

-- Indexes
CREATE INDEX idx_reflection_questions_user_id ON reflection_questions(user_id);
CREATE INDEX idx_reflection_answers_user_id ON reflection_answers(user_id);
CREATE INDEX idx_reflection_answers_completion_id ON reflection_answers(completion_id);
