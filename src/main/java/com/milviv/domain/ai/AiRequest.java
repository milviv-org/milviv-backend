package com.milviv.domain.ai;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.milviv.domain.exception.AiRequestException;
import com.milviv.domain.shared.VersionedAggregate;

/**
 * Один запрос пользователя к AI (§6.8). Сырое сообщение не хранится — только длина и SHA-256 (§13).
 *
 * Правила:
 * - из PENDING_AI выходит ровно один раз — в итоговый статус;
 * - PENDING_AI старше таймаута запроса считается зависшим (сервер упал во время вызова AI);
 * - PROPOSED ждёт решения пользователя до expiresAt; решение — одно (EXECUTED или REJECTED).
 */
public class AiRequest extends VersionedAggregate {

    private final UUID id;
    private final UUID userId;
    private final String idempotencyKey;
    private final String requestHash;
    private final int messageLength;
    private final String messageHash;
    private final String contractVersion;
    private final String promptVersion;
    private final String provider;
    private final String model;
    private final Instant createdAt;

    private AiRequestStatus status;
    private String summary;
    private String answer;
    private String clarificationQuestion;
    private RefusalReason refusalReason;
    private String errorCode;
    private int aiCalls;
    private Integer inputTokens;
    private Integer outputTokens;
    private Integer latencyMs;
    private OperationReport report;
    private Instant executedAt;
    private List<AiHandle> handles;
    private List<DataAccess> dataAccessLog;
    private int dataRounds;
    private ValidatedPlan plan;
    private AiDecision decision;
    private Instant expiresAt;

    /** Восстановление из БД. Вызывается только репозиторием. */
    public AiRequest(UUID id, UUID userId, AiRequestStatus status, String idempotencyKey, String requestHash,
                     int messageLength, String messageHash, String summary, String answer, String clarificationQuestion,
                     RefusalReason refusalReason, String errorCode, String contractVersion, String promptVersion,
                     String provider, String model, int aiCalls, Integer inputTokens, Integer outputTokens,
                     Integer latencyMs, OperationReport report, List<AiHandle> handles, List<DataAccess> dataAccessLog,
                     int dataRounds, ValidatedPlan plan, AiDecision decision, Instant createdAt,
                     Instant expiresAt, Instant executedAt, long version) {
        super(version);
        this.id = id;
        this.userId = userId;
        this.status = status;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.messageLength = messageLength;
        this.messageHash = messageHash;
        this.summary = summary;
        this.answer = answer;
        this.clarificationQuestion = clarificationQuestion;
        this.refusalReason = refusalReason;
        this.errorCode = errorCode;
        this.contractVersion = contractVersion;
        this.promptVersion = promptVersion;
        this.provider = provider;
        this.model = model;
        this.aiCalls = aiCalls;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.latencyMs = latencyMs;
        this.report = report;
        this.handles = handles == null ? List.of() : List.copyOf(handles);
        this.dataAccessLog = dataAccessLog == null ? List.of() : List.copyOf(dataAccessLog);
        this.dataRounds = dataRounds;
        this.plan = plan;
        this.decision = decision;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.executedAt = executedAt;
    }

    public static AiRequest start(UUID userId, String idempotencyKey, String requestHash, int messageLength,
                                  String messageHash, String contractVersion, String promptVersion,
                                  String provider, String model, Instant now) {
        return new AiRequest(UUID.randomUUID(), userId, AiRequestStatus.PENDING_AI, idempotencyKey, requestHash,
                messageLength, messageHash, null, null, null, null, null, contractVersion, promptVersion,
                provider, model, 0, null, null, null, null, List.of(), List.of(), 0, null, null, now, null, null, 0L);
    }

    /** Расход на все вызовы AI в этом запросе. Токены null — провайдер их не сообщил. */
    public record Usage(int aiCalls, Integer inputTokens, Integer outputTokens, int latencyMs) {}

    public void answered(String answer, Usage usage) {
        finish(AiRequestStatus.ANSWERED, usage);
        this.answer = answer;
    }

    /**
     * Что AI прочитал за запрос и какие дескрипторы получил. Записывается при любом итоге,
     * в том числе при ошибке: журнал — доказательство, какие данные ушли провайдеру (§13).
     */
    public void recordDataAccess(List<AiHandle> handles, List<DataAccess> dataAccessLog, int dataRounds) {
        if (!isPending()) {
            throw new IllegalStateException("AiRequest " + id + " is already " + status);
        }
        this.handles = List.copyOf(handles);
        this.dataAccessLog = List.copyOf(dataAccessLog);
        this.dataRounds = dataRounds;
    }

    /**
     * План исполнен целиком: в нём были только создания (G-25).
     *
     * @param answer текст AI рядом с операциями (G-21); может быть null
     */
    public void executed(String summary, String answer, OperationReport report, Usage usage, Instant now) {
        finish(AiRequestStatus.EXECUTED, usage);
        this.summary = summary;
        this.answer = answer;
        this.report = report;
        this.executedAt = now;
    }

    /**
     * Создания исполнены, изменения и удаления ждут согласия пользователя (G-3).
     *
     * @param report    что уже создано
     * @param expiresAt до какого момента можно решить (TTL плана)
     */
    public void proposed(String summary, String answer, OperationReport report, ValidatedPlan plan, Instant expiresAt,
                         Usage usage) {
        finish(AiRequestStatus.PROPOSED, usage);
        this.summary = summary;
        this.answer = answer;
        this.report = report;
        this.plan = plan;
        this.expiresAt = expiresAt;
    }

    /** Истёк TTL или план сохранён в формате другой версии. */
    public boolean isExpired(Instant now) {
        return status == AiRequestStatus.PROPOSED
                && (!now.isBefore(expiresAt) || plan.format() != ValidatedPlan.FORMAT);
    }

    public void expire() {
        requireProposed();
        this.status = AiRequestStatus.EXPIRED;
    }

    /**
     * Решение пользователя: подтверждено хоть что-то — EXECUTED, ничего — REJECTED.
     *
     * @param report весь отчёт запроса: созданное + изменённое, удалённое, отменённое
     */
    public void decided(AiDecision decision, OperationReport report, Instant now) {
        requireProposed();
        this.status = decision.approved().isEmpty() ? AiRequestStatus.REJECTED : AiRequestStatus.EXECUTED;
        this.decision = decision;
        this.report = report;
        if (status == AiRequestStatus.EXECUTED) {
            this.executedAt = now;
        }
    }

    private void requireProposed() {
        if (status != AiRequestStatus.PROPOSED) {
            throw new IllegalStateException("AiRequest " + id + " is " + status + ", not PROPOSED");
        }
    }

    public void clarificationNeeded(String question, Usage usage) {
        finish(AiRequestStatus.NEEDS_CLARIFICATION, usage);
        this.clarificationQuestion = question;
    }

    /** Отказ AI — ошибка для клиента (422 AI_REFUSED), но причина сохраняется. */
    public void refused(RefusalReason reason, Usage usage) {
        finish(AiRequestStatus.FAILED, usage);
        this.errorCode = AiRequestException.Code.AI_REFUSED.name();
        this.refusalReason = reason;
    }

    public void failed(AiRequestException.Code code, Usage usage) {
        finish(AiRequestStatus.FAILED, usage);
        this.errorCode = code.name();
    }

    public boolean isPending() {
        return status == AiRequestStatus.PENDING_AI;
    }

    public boolean isStale(Instant now, Duration requestTimeout) {
        return isPending() && createdAt.plus(requestTimeout).isBefore(now);
    }

    public boolean hasSameBody(String otherRequestHash) {
        return requestHash.equals(otherRequestHash);
    }

    private void finish(AiRequestStatus next, Usage usage) {
        if (!isPending()) {
            throw new IllegalStateException("AiRequest " + id + " is already " + status);
        }
        this.status = next;
        this.aiCalls = usage.aiCalls();
        this.inputTokens = usage.inputTokens();
        this.outputTokens = usage.outputTokens();
        this.latencyMs = usage.latencyMs();
    }

    public UUID getId()                       { return id; }
    public UUID getUserId()                   { return userId; }
    public AiRequestStatus getStatus()        { return status; }
    public String getIdempotencyKey()         { return idempotencyKey; }
    public String getRequestHash()            { return requestHash; }
    public int getMessageLength()             { return messageLength; }
    public String getMessageHash()            { return messageHash; }
    public String getSummary()                { return summary; }
    public String getAnswer()                 { return answer; }
    public String getClarificationQuestion()  { return clarificationQuestion; }
    public RefusalReason getRefusalReason()   { return refusalReason; }
    public String getErrorCode()              { return errorCode; }
    public String getContractVersion()        { return contractVersion; }
    public String getPromptVersion()          { return promptVersion; }
    public String getProvider()               { return provider; }
    public String getModel()                  { return model; }
    public int getAiCalls()                   { return aiCalls; }
    public Integer getInputTokens()           { return inputTokens; }
    public Integer getOutputTokens()          { return outputTokens; }
    public Integer getLatencyMs()             { return latencyMs; }
    public OperationReport getReport()        { return report; }
    public Instant getCreatedAt()             { return createdAt; }
    public Instant getExecutedAt()            { return executedAt; }
    public List<AiHandle> getHandles()        { return handles; }
    public List<DataAccess> getDataAccessLog() { return dataAccessLog; }
    public int getDataRounds()                { return dataRounds; }
    public ValidatedPlan getPlan()            { return plan; }
    public AiDecision getDecision()           { return decision; }
    public Instant getExpiresAt()             { return expiresAt; }
}
