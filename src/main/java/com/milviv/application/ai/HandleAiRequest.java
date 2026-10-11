package com.milviv.application.ai;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import com.milviv.application.ai.AiAssistantPort.AiPrompt;
import com.milviv.application.ai.AiAssistantPort.AiReply;
import com.milviv.application.ai.contract.AiTurn;
import com.milviv.application.transaction.Propagation;
import com.milviv.application.transaction.TransactionManager;
import com.milviv.domain.ai.AiRequest;
import com.milviv.domain.ai.AiRequestRepository;
import com.milviv.domain.ai.AiRequestStatus;
import com.milviv.domain.ai.OperationReport;
import com.milviv.domain.ai.RefusalReason;
import com.milviv.domain.exception.AiRequestException;
import com.milviv.domain.exception.AiRequestException.Code;
import com.milviv.domain.exception.DomainException;
import com.milviv.domain.exception.NotFoundException;
import com.milviv.domain.exception.ValidationException;

/**
 * Фаза 1 AI-запроса (§5, §17): согласие → повтор и активный запрос → вызовы AI → итог.
 *
 * Три шага, две короткие транзакции:
 * 1. tx: блокировка пользователя, Idempotency-Key, один активный запрос → PENDING_AI;
 * 2. вызовы AI — вне транзакции (AD-08, инвариант 9);
 * 3. tx: итог записывается, только если запрос всё ещё PENDING_AI.
 *
 * AI отвечает, уточняет, отказывается или предлагает создания — они исполняются сразу,
 * в транзакции 3 (G-25). Запросы данных — 9.4, изменения и удаления — 9.5.
 */
public class HandleAiRequest {

    public static final int MAX_PAGE_SIZE = 100;

    /** Часового пояса пользователя в системе нет — все даты по UTC (K-4). */
    static final String TIMEZONE = "UTC";
    static final String LOCALE = "ru-RU";

    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9_-]{1,100}$");

    private final AiConsentService consentService;
    private final AiAssistantPort assistant;
    private final AiTurnValidator validator;
    private final ExecuteAiPlan executeAiPlan;
    private final AiRequestRepository requests;
    private final AiLimits limits;
    private final TransactionManager transactionManager;
    private final Clock clock;

    public HandleAiRequest(AiConsentService consentService, AiAssistantPort assistant, AiTurnValidator validator,
                           ExecuteAiPlan executeAiPlan, AiRequestRepository requests, AiLimits limits,
                           TransactionManager transactionManager, Clock clock) {
        this.consentService = consentService;
        this.assistant = assistant;
        this.validator = validator;
        this.executeAiPlan = executeAiPlan;
        this.requests = requests;
        this.limits = limits;
        this.transactionManager = transactionManager;
        this.clock = clock;
    }

    /** @param created false — повтор с тем же ключом, ответ взят из сохранённого запроса */
    public record Result(AiRequestView request, boolean created) {}

    /** Итог разговора с AI, который запишется в транзакции 3. */
    private record Outcome(AiTurn turn, Code failure, AiRequest.Usage usage) {}

    /** Ход 1: либо новый запрос, либо уже существующий (повтор). */
    private record Start(AiRequest request, boolean replay) {}

    /**
     * @throws AiRequestException запрос завершился ошибкой (в том числе при повторе — та же ошибка)
     */
    public Result execute(UUID userId, String idempotencyKey, String rawMessage) {
        consentService.requireConsent(userId);
        if (idempotencyKey == null || !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            throw new ValidationException("Idempotency-Key", "Обязательный заголовок: 1–100 символов, A-Z, a-z, 0-9, «-», «_»");
        }
        String message = rawMessage == null ? "" : rawMessage.strip();
        if (message.isEmpty()) {
            throw new ValidationException("message", "Сообщение не может быть пустым");
        }
        if (message.length() > limits.request().messageMaxLength()) {
            throw new ValidationException("message", "Сообщение длиннее " + limits.request().messageMaxLength() + " символов");
        }

        // тело запроса пока — только сообщение, поэтому request_hash = message_hash;
        // при новых полях тела request_hash будет считаться по ним всем
        String hash = sha256(message);
        Instant startedAt = clock.instant();
        Start start = transactionManager.execute(() -> begin(userId, idempotencyKey, hash, message.length(), startedAt));
        if (start.replay()) {
            return result(start.request(), false);
        }

        Outcome outcome = converse(message, startedAt);
        UUID requestId = start.request().getId();
        AiRequest finished;
        try {
            finished = transactionManager.execute(() -> finish(userId, requestId, outcome));
        } catch (DomainException ex) {
            // правило домена нарушено при исполнении: созданное откатилось вместе с транзакцией (§10.1)
            finished = transactionManager.execute(() -> fail(userId, requestId, Code.BUSINESS_RULE_VIOLATION, outcome.usage()));
        }
        return result(finished, true);
    }

    /** Без согласия недоступно всё /ai/requests* (§6.10), в том числе история. */
    public AiRequestView get(UUID userId, UUID requestId) {
        consentService.requireConsent(userId);
        return transactionManager.execute(Propagation.SUPPORTS, () -> requests.findById(userId, requestId))
                .map(AiRequestView::from)
                .orElseThrow(() -> new NotFoundException("Запрос не найден"));
    }

    public List<AiRequestView> list(UUID userId, int limit, int offset) {
        consentService.requireConsent(userId);
        int pageSize = Math.clamp(limit, 1, MAX_PAGE_SIZE);
        int from = Math.max(offset, 0);
        return transactionManager.execute(Propagation.SUPPORTS, () -> requests.findByUser(userId, pageSize, from))
                .stream()
                .map(AiRequestView::from)
                .toList();
    }

    // ─── Транзакция 1 ─────────────────────────────────────────────────────────

    private Start begin(UUID userId, String idempotencyKey, String hash, int messageLength, Instant now) {
        requests.lockUser(userId);
        Duration timeout = limits.request().requestTimeout();

        var existing = requests.findByIdempotencyKey(userId, idempotencyKey);
        if (existing.isPresent()) {
            AiRequest request = existing.get();
            if (!request.hasSameBody(hash)) {
                throw new AiRequestException(Code.IDEMPOTENCY_KEY_REUSED);
            }
            if (request.isStale(now, timeout)) {
                markStale(request);
            } else if (request.isPending()) {
                throw new AiRequestException(Code.AI_REQUEST_IN_PROGRESS);
            }
            return new Start(request, true);
        }

        var pending = requests.findPending(userId);
        if (pending.isPresent()) {
            if (!pending.get().isStale(now, timeout)) {
                throw new AiRequestException(Code.AI_REQUEST_IN_PROGRESS);
            }
            // сервер упал во время вызова AI — иначе пользователь застрял бы навсегда (§10.2)
            markStale(pending.get());
        }

        AiRequest request = AiRequest.start(userId, idempotencyKey, hash, messageLength, hash,
                AiContract.CONTRACT_VERSION, AiContract.PROMPT_VERSION, assistant.provider(), assistant.model(), now);
        requests.add(request);
        return new Start(request, false);
    }

    private void markStale(AiRequest request) {
        request.failed(Code.AI_TIMEOUT, new AiRequest.Usage(request.getAiCalls(), request.getInputTokens(),
                request.getOutputTokens(), (int) limits.request().requestTimeout().toMillis()));
        requests.update(request);
    }

    // ─── Вызовы AI, вне транзакции ────────────────────────────────────────────

    private Outcome converse(String message, Instant startedAt) {
        Duration timeout = limits.request().requestTimeout();
        LocalDate today = LocalDate.ofInstant(startedAt, ZoneOffset.UTC);
        Tokens tokens = new Tokens();
        List<String> violations = List.of();
        boolean repaired = false;
        int calls = 0;

        while (true) {
            Duration timeLeft = timeout.minus(Duration.between(startedAt, clock.instant()));
            if (timeLeft.isNegative() || timeLeft.isZero()) {
                return failure(Code.AI_TIMEOUT, calls, tokens, startedAt);
            }
            if (calls >= limits.request().maxCallsPerRequest()) {
                return failure(Code.AI_INVALID_RESPONSE, calls, tokens, startedAt);
            }

            AiReply reply;
            calls++;
            try {
                reply = assistant.nextTurn(new AiPrompt(AiContract.CONTRACT_VERSION, AiContract.PROMPT_VERSION,
                        LOCALE, TIMEZONE, today, message, calls, violations, timeLeft, limits.plan()));
            } catch (AiProviderException ex) {
                switch (ex.getKind()) {
                    case UNAVAILABLE -> { return failure(Code.AI_PROVIDER_UNAVAILABLE, calls, tokens, startedAt); }
                    case TIMEOUT -> { return failure(Code.AI_TIMEOUT, calls, tokens, startedAt); }
                    case MALFORMED -> {
                        // неразобранный ответ — тот же неверный ход: один шанс исправиться
                        if (repaired) {
                            return failure(Code.AI_INVALID_RESPONSE, calls, tokens, startedAt);
                        }
                        repaired = true;
                        violations = List.of("response: MALFORMED");
                        continue;
                    }
                }
                throw ex;
            }
            tokens.add(reply);

            if (Duration.between(startedAt, clock.instant()).compareTo(timeout) > 0) {
                return failure(Code.AI_TIMEOUT, calls, tokens, startedAt);
            }

            AiTurn turn = validator.normalize(reply.turn());
            AiPlanPolicy.Verdict verdict = validator.validate(turn, today);
            if (verdict.ok()) {
                return new Outcome(turn, null, tokens.usage(calls, startedAt, clock.instant()));
            }
            // слишком большой план не исправляется повтором — AI молча выбросил бы часть операций
            if (verdict.tooLarge()) {
                return failure(Code.AI_PLAN_TOO_LARGE, calls, tokens, startedAt);
            }
            List<String> found = verdict.violations();
            // один повтор с замечаниями; снова неверно → AI_INVALID_RESPONSE (§7.1)
            if (repaired) {
                return failure(Code.AI_INVALID_RESPONSE, calls, tokens, startedAt);
            }
            repaired = true;
            violations = found;
        }
    }

    private Outcome failure(Code code, int calls, Tokens tokens, Instant startedAt) {
        return new Outcome(null, code, tokens.usage(calls, startedAt, clock.instant()));
    }

    /** Сумма токенов по вызовам; null, если провайдер ни разу их не сообщил. */
    private static final class Tokens {
        private Integer input;
        private Integer output;

        void add(AiReply reply) {
            input = sum(input, reply.inputTokens());
            output = sum(output, reply.outputTokens());
        }

        AiRequest.Usage usage(int calls, Instant startedAt, Instant now) {
            return new AiRequest.Usage(calls, input, output, (int) Duration.between(startedAt, now).toMillis());
        }

        private static Integer sum(Integer total, Integer value) {
            return value == null ? total : (total == null ? value : total + value);
        }
    }

    // ─── Транзакция 3 ─────────────────────────────────────────────────────────

    /**
     * Запрос перечитывается: пока шёл вызов AI, его могли признать зависшим.
     * Тогда итог AI не записывается — остаётся то, что уже сохранено.
     */
    private AiRequest finish(UUID userId, UUID requestId, Outcome outcome) {
        AiRequest request = requests.findById(userId, requestId).orElseThrow();
        if (!request.isPending()) {
            return request;
        }
        if (outcome.failure() != null) {
            request.failed(outcome.failure(), outcome.usage());
        } else {
            switch (outcome.turn()) {
                case AiTurn.Answer a -> request.answered(a.answer(), outcome.usage());
                case AiTurn.ClarificationNeeded c -> request.clarificationNeeded(c.clarificationQuestion(), outcome.usage());
                case AiTurn.Refused r -> request.refused(r.refusalReason(), outcome.usage());
                case AiTurn.Proposal p -> {
                    OperationReport report = executeAiPlan.executeCreations(userId, p.plan().create());
                    request.executed(p.summary(), p.answer(), report, outcome.usage(), clock.instant());
                }
            }
        }
        requests.update(request);
        return request;
    }

    private AiRequest fail(UUID userId, UUID requestId, Code code, AiRequest.Usage usage) {
        AiRequest request = requests.findById(userId, requestId).orElseThrow();
        if (request.isPending()) {
            request.failed(code, usage);
            requests.update(request);
        }
        return request;
    }

    // ─── Ответ ────────────────────────────────────────────────────────────────

    private static Result result(AiRequest request, boolean created) {
        if (request.getStatus() == AiRequestStatus.FAILED) {
            Code code = Code.valueOf(request.getErrorCode());
            throw code == Code.AI_REFUSED
                    ? AiRequestException.refused(request.getRefusalReason() != null ? request.getRefusalReason() : RefusalReason.OTHER)
                    : new AiRequestException(code);
        }
        return new Result(AiRequestView.from(request), created);
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }
}
