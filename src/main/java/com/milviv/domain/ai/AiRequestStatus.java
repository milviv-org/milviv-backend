package com.milviv.domain.ai;

/** Состояния AI-запроса (§6.8). Из PENDING_AI — в любое другое; дальше — только из PROPOSED. */
public enum AiRequestStatus {
    PENDING_AI,
    EXECUTED,
    PROPOSED,
    ANSWERED,
    NEEDS_CLARIFICATION,
    FAILED,
    REJECTED,
    EXPIRED
}
