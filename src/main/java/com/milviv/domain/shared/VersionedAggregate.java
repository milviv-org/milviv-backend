package com.milviv.domain.shared;

/**
 * Базовый класс для агрегатов с optimistic locking.
 *
 * version используется в WHERE clause при UPDATE:
 *   UPDATE ... WHERE id = ? AND version = ?
 * Если UPDATE затронул 0 строк — бросаем OptimisticLockException.
 *
 * incrementVersion() вызывается только из репозитория после успешного UPDATE.
 * Domain не управляет версией напрямую.
 */
public abstract class VersionedAggregate {
  private long version;

  protected VersionedAggregate(long version) {
    this.version = version;
  }

  public long getVersion() {
    return version;
  }

  /**
   * Вызывать только из Infrastructure репозитория после успешного UPDATE.
   * Синхронизирует in-memory состояние с состоянием в БД.
   */
  public void incrementVersion() {
    this.version++;
  }
}
