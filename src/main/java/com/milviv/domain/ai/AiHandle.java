package com.milviv.domain.ai;

import java.time.LocalDate;
import java.util.UUID;

import com.milviv.domain.ai.OperationReport.EntityType;

/**
 * Дескриптор, выданный AI вместо ID (§6.4). Действует в рамках одного AI-запроса.
 *
 * @param version  версия сущности на момент чтения — по ней 9.5 узнает, что сущность изменилась
 * @param deadline срок проекта на момент чтения — для проверки срока новых задач; у остальных null
 */
public record AiHandle(String handle, EntityType type, UUID id, long version, LocalDate deadline) {}
