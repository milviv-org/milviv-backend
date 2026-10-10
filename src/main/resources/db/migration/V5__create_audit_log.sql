-- V5: Create audit log table for user actions history
CREATE TABLE IF NOT EXISTS audit_log (
    id BIGSERIAL PRIMARY KEY,
    user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    action VARCHAR(50) NOT NULL, -- TASK_CREATED, TASK_UPDATED, TASK_DELETED, PROJECT_CREATED, etc.
    entity_type VARCHAR(50) NOT NULL, -- GOAL, PROJECT, TASK, REWARD, PLAN
    entity_id UUID,
    details JSONB, -- например {"source": "AI", "requestId": "...", "fields": ["deadline"]}
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Indexes for fast querying
CREATE INDEX idx_audit_log_user_id ON audit_log(user_id);
CREATE INDEX idx_audit_log_action ON audit_log(action);
CREATE INDEX idx_audit_log_entity ON audit_log(entity_type, entity_id);
CREATE INDEX idx_audit_log_created_at ON audit_log(created_at DESC);
CREATE INDEX idx_audit_log_details ON audit_log USING gin(details); -- For JSONB queries
