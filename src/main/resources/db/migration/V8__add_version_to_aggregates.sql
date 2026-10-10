-- Optimistic locking: version column для всех таблиц-агрегатов.
-- Значение 0 для существующих строк — это стартовая версия.

ALTER TABLE users    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE tasks    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE projects ADD COLUMN version BIGINT NOT NULL DEFAULT 0;