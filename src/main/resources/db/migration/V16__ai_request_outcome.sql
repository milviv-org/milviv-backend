-- V16: Исходы AI-запроса, для которых в V14 нет места (подэтап 9.2)
-- Уточняющий вопрос AI (статус NEEDS_CLARIFICATION) и причина отказа (FAILED + error_code AI_REFUSED).
ALTER TABLE ai_requests ADD COLUMN clarification_question TEXT;
ALTER TABLE ai_requests ADD COLUMN refusal_reason VARCHAR(30)
    CHECK (refusal_reason IN ('OFF_TOPIC', 'UNSUPPORTED_OPERATION', 'OTHER'));
