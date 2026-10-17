package com.milviv.application.ai;

/** Версии контракта и промпта (§14). Пишутся в каждый AI-запрос. */
public final class AiContract {

    public static final String CONTRACT_VERSION = "assistant.v1";
    /** @2 — инструкция учитывает память AI (9.8). */
    public static final String PROMPT_VERSION = "assistant@2";

    private AiContract() {}
}
