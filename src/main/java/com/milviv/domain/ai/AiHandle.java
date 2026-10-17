package com.milviv.domain.ai;

import java.time.LocalDate;
import java.util.UUID;

import com.milviv.domain.ai.OperationReport.EntityType;

/**
 * Дескриптор, выданный AI вместо ID (§6.4). Действует в рамках одного AI-запроса.
 *
 * @param version   версия сущности на момент чтения: изменилась — TARGET_CHANGED
 * @param deadline  срок проекта или задачи на момент чтения — для проверки дат плана; у остальных null
 * @param projectId проект задачи на момент чтения; у остальных null
 */
public record AiHandle(String handle, EntityType type, UUID id, long version, LocalDate deadline, UUID projectId) {}
