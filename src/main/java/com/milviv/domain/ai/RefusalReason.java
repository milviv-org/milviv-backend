package com.milviv.domain.ai;

/** Почему AI отказался (§6.2). UNSUPPORTED_OPERATION — например, «выполни задачу» (G-16). */
public enum RefusalReason {
    OFF_TOPIC,
    UNSUPPORTED_OPERATION,
    OTHER
}
