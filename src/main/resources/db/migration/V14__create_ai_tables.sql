-- V14: Create AI Assistant tables

-- Согласие на использование данных: даётся один раз, без него AI недоступен (G-12, G-24)
CREATE TABLE IF NOT EXISTS ai_consents (
    user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    accepted_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Один запрос пользователя к AI: состояние, план, согласие на изменения, отчёт.
-- Сырое сообщение не хранится — только длина и хеш.
CREATE TABLE IF NOT EXISTS ai_requests (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    status VARCHAR(30) NOT NULL CHECK (status IN (
        'PENDING_AI', 'EXECUTED', 'PROPOSED', 'ANSWERED',
        'NEEDS_CLARIFICATION', 'FAILED', 'REJECTED', 'EXPIRED')),
    idempotency_key VARCHAR(100) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    message_length INTEGER NOT NULL,
    message_hash VARCHAR(64) NOT NULL,
    handles JSONB,          -- дескриптор → (тип, id, версия)
    data_access_log JSONB,  -- что AI прочитал, без содержимого
    plan JSONB,             -- проверенный план
    decision JSONB,         -- подтверждённые пользователем изменения/удаления
    result JSONB,           -- отчёт об операциях
    answer TEXT,
    contract_version VARCHAR(30) NOT NULL,
    prompt_version VARCHAR(50) NOT NULL,
    provider VARCHAR(30) NOT NULL,
    model VARCHAR(100) NOT NULL,
    error_code VARCHAR(50),
    ai_calls INTEGER NOT NULL DEFAULT 0,
    data_rounds INTEGER NOT NULL DEFAULT 0,
    input_tokens INTEGER,
    output_tokens INTEGER,
    latency_ms INTEGER,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP,
    executed_at TIMESTAMP,
    UNIQUE (user_id, idempotency_key)
);

-- Один активный запрос на пользователя
CREATE UNIQUE INDEX uq_ai_requests_one_pending ON ai_requests(user_id) WHERE status = 'PENDING_AI';

-- Выводы, которые пользователь сохранил в памяти AI (AI Memory)
CREATE TABLE IF NOT EXISTS ai_memories (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    content TEXT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Indexes
CREATE INDEX idx_ai_requests_user_created ON ai_requests(user_id, created_at DESC);
CREATE INDEX idx_ai_memories_user_id ON ai_memories(user_id);
