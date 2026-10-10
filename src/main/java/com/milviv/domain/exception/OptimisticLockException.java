package com.milviv.domain.exception;

import java.util.UUID;

/**
 * Бросается когда UPDATE затронул 0 строк из-за несоответствия версии.
 * Означает: кто-то изменил запись между вашим чтением и записью.
 *
 * HTTP статус: 409 Conflict.
 * Логируется как INFO (ожидаемый flow при параллельных запросах).
 */
public final class OptimisticLockException extends DomainException {
  
  public OptimisticLockException(String entityType, UUID entityId, long expectedVersion) {
    super(String.format(
      "%s [id=%s] was modified concurrently. Expected version=%d Please reload and retry.",
      entityType, entityId, expectedVersion
    ));
  }

  @Override
  public String getErrorCode() {
    return "OPTIMISTIC_LOCK";
  }
}
