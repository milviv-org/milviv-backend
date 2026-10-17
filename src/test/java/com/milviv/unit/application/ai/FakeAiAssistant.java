package com.milviv.unit.application.ai;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import com.milviv.application.ai.AiAssistantPort;
import com.milviv.application.ai.AiProviderException;
import com.milviv.application.ai.contract.AiTurn;

/**
 * Провайдер для тестов (§12): отдаёт заранее заданные ходы или сбои по очереди
 * и запоминает, что ему передали.
 */
public class FakeAiAssistant implements AiAssistantPort {

    private final Deque<Object> script = new ArrayDeque<>();
    private final List<AiPrompt> prompts = new ArrayList<>();
    private Consumer<AiPrompt> beforeReply = prompt -> {};
    private boolean reportTokens = true;

    public FakeAiAssistant thenTurn(AiTurn turn) {
        script.add(turn);
        return this;
    }

    /** Ход, который зависит от присланного, — например, ссылается на выданный дескриптор. */
    public FakeAiAssistant thenTurn(Function<AiPrompt, AiTurn> turn) {
        script.add(turn);
        return this;
    }

    public FakeAiAssistant thenFail(AiProviderException.Kind kind) {
        script.add(kind);
        return this;
    }

    /** Сбой с замечаниями — как MALFORMED от разбора ответа. */
    public FakeAiAssistant thenFail(AiProviderException failure) {
        script.add(failure);
        return this;
    }

    /** Действие во время «вызова», например сдвинуть часы, чтобы истёк таймаут. */
    public FakeAiAssistant duringCall(Consumer<AiPrompt> action) {
        this.beforeReply = action;
        return this;
    }

    /** Провайдер, который не сообщает токены. */
    public FakeAiAssistant withoutTokens() {
        this.reportTokens = false;
        return this;
    }

    public List<AiPrompt> prompts() {
        return prompts;
    }

    @Override
    public String provider() {
        return "fake";
    }

    @Override
    public String model() {
        return "fake-1";
    }

    @Override
    public AiReply nextTurn(AiPrompt prompt) {
        prompts.add(prompt);
        beforeReply.accept(prompt);
        Object next = script.poll();
        if (next == null) {
            throw new IllegalStateException("FakeAiAssistant: script is empty, call " + prompt.call());
        }
        if (next instanceof AiProviderException failure) {
            throw failure;
        }
        if (next instanceof AiProviderException.Kind kind) {
            throw new AiProviderException(kind, "fake failure");
        }
        if (next instanceof Function<?, ?> function) {
            @SuppressWarnings("unchecked")
            Function<AiPrompt, AiTurn> dynamic = (Function<AiPrompt, AiTurn>) function;
            return reply(dynamic.apply(prompt));
        }
        return reply((AiTurn) next);
    }

    private AiReply reply(AiTurn turn) {
        return reportTokens ? new AiReply(turn, 10, 5) : new AiReply(turn, null, null);
    }

    /** Часы, которые тест двигает вручную. */
    public static final class MutableClock extends Clock {
        private java.time.Instant now;

        public MutableClock(java.time.Instant start) {
            this.now = start;
        }

        public void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public java.time.Instant instant() { return now; }
    }
}
