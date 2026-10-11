package com.milviv.infrastructure.ai;

import com.milviv.application.ai.AiAssistantPort;
import com.milviv.application.ai.AiProviderException;

/**
 * Провайдер, пока адаптера Gemini нет (подэтап 9.6): каждый вызов — «недоступен».
 * POST /ai/requests проверяет согласие, записывает запрос и отвечает 503 AI_PROVIDER_UNAVAILABLE.
 */
public class UnavailableAiAssistant implements AiAssistantPort {

    @Override
    public String provider() {
        return "none";
    }

    @Override
    public String model() {
        return "none";
    }

    @Override
    public AiReply nextTurn(AiPrompt prompt) {
        throw new AiProviderException(AiProviderException.Kind.UNAVAILABLE, "AI provider is not configured");
    }
}
