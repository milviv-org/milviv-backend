-- V6: Create error log table for application errors
CREATE TABLE IF NOT EXISTS error_log (
    id BIGSERIAL PRIMARY KEY,
    timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    level VARCHAR(10) NOT NULL, -- ERROR, WARN
    logger_name VARCHAR(255),
    message TEXT NOT NULL,
    exception_class VARCHAR(255),
    exception_message TEXT,
    stack_trace TEXT,
    request_id VARCHAR(50), -- To correlate multiple log entries from same request
    user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    endpoint VARCHAR(255), -- HTTP endpoint if applicable
    http_method VARCHAR(10) -- GET, POST, etc.
);

-- Indexes
CREATE INDEX idx_error_log_timestamp ON error_log(timestamp DESC);
CREATE INDEX idx_error_log_level ON error_log(level);
CREATE INDEX idx_error_log_request_id ON error_log(request_id);
CREATE INDEX idx_error_log_user_id ON error_log(user_id);
