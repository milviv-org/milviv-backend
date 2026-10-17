package com.milviv.application.ai;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import com.milviv.application.ai.AiAssistantPort.AiPrompt;
import com.milviv.application.ai.AiAssistantPort.AiReply;
import com.milviv.application.ai.contract.AiTurn;
import com.milviv.application.ai.contract.DataRound;
import com.milviv.application.transaction.Propagation;
import com.milviv.application.transaction.TransactionManager;
import com.milviv.domain.ai.AiMemory;
import com.milviv.domain.ai.AiMemoryRepository;
import com.milviv.domain.ai.AiRequest;
import com.milviv.domain.ai.AiRequestRepository;
import com.milviv.domain.ai.AiRequestStatus;
import com.milviv.domain.ai.DataAccess;
import com.milviv.domain.ai.ValidatedPlan;
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
 * 1. tx: блокировка пользователя, Idempotency-Key, один активный запрос, предохранитель квоты провайдера,
 *    квота пользователя → PENDING_AI;
 * 2. вызовы AI — вне транзакции (AD-08, инвариант 9);
 * 3. tx: итог записывается, только если запрос всё ещё PENDING_AI.
 *
 * AI запрашивает данные из каталога (≤ maxRounds раундов), затем отвечает, уточняет, отказывается
 * или предлагает план. Создания исполняются сразу, в транзакции 3 (G-25); изменения и удаления
 * попадают в карточку (PROPOSED) и ждут согласия пользователя — их исполняет DecideAiProposal.
 */
public class HandleAiRequest {

    public static final int MAX_PAGE_SIZE = 100;

    /** Часового пояса пользователя в системе нет — все даты по UTC (K-4). */
    static final String TIMEZONE = "UTC";
    static final String LOCALE = "ru-RU";

    /** Запись журнала прочитанного: память AI передана провайдеру. */
    static final String MEMORY_ACCESS = "MEMORY";

    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9_-]{1,100}$");

    private final AiConsentService consentService;
    private final AiAssistantPort assistant;
    private final AiTurnValidator validator;
    private final ExecuteAiPlan executeAiPlan;
    private final PreviewBuilder previewBuilder;
    private final AiDataCatalog catalog;
    private final AiRequestRepository requests;
    private final AiLimits limits;
    private final AiUsageQuota usageQuota;
    private final ProviderQuotaBreaker breaker;
    private final AiMemoryRepository memories;
    private final TransactionManager transactionManager;
    private final Clock clock;

    public HandleAiRequest(AiConsentService consentService, AiAssistantPort assistant, AiTurnValidator validator,
                           ExecuteAiPlan executeAiPlan, PreviewBuilder previewBuilder, AiDataCatalog catalog,
                           AiRequestRepository requests, AiLimits limits, AiUsageQuota usageQuota,
                           ProviderQuotaBreaker breaker, AiMemoryRepository memories, TransactionManager transactionManager,
                           Clock clock) {
        this.consentService = consentService;
        this.assistant = assistant;
        this.validator = validator;
        this.executeAiPlan = executeAiPlan;
        this.previewBuilder = previewBuilder;
        this.catalog = catalog;
        this.requests = requests;
        this.limits = limits;
        this.usageQuota = usageQuota;
        this.breaker = breaker;
        this.memories = memories;
        this.transactionManager = transactionManager;
        this.clock = clock;
    }

    /** @param created false — повтор с тем же ключом, ответ взят из сохранённого запроса */
    public record Result(AiRequestView request, boolean created) {}

    /** Что накопилось за разговор с AI: дескрипторы, раунды данных, журнал прочитанного. */
    private static final class Conversation {
        final HandleRegistry registry = new HandleRegistry();
        final List<DataRound> rounds = new ArrayList<>();
        final List<DataAccess> accessLog = new ArrayList<>();
        final AiDataCatalog.Budget budget;

        Conversation(int tokens) {
            this.budget = new AiDataCatalog.Budget(tokens);
        }
    }

    /** Итог разговора с AI, который запишется в транзакции 3. */
    private record Outcome(AiTurn turn, Code failure, AiRequest.Usage usage, Conversation conversation) {}

    /**
     * Ход 1: либо новый запрос, либо уже существующий (повтор).
     *
     * @param createsLeft сколько ещё можно создать через AI в текущих сутках
     * @param memories    память AI пользователя на момент начала запроса
     */
    private record Start(AiRequest request, boolean replay, int createsLeft, List<String> memories) {}

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

        Outcome outcome = converse(userId, message, startedAt, start.createsLeft(), start.memories());
        UUID requestId = start.request().getId();
        AiRequest finished;
        try {
            finished = transactionManager.execute(() -> finish(userId, requestId, outcome));
        } catch (AiRequestException ex) {
            // сущность из плана изменилась после чтения AI: созданное откатилось вместе с транзакцией
            finished = transactionManager.execute(() -> fail(userId, requestId, ex.getCode(), outcome));
        } catch (DomainException ex) {
            // правило домена нарушено при исполнении: созданное откатилось вместе с транзакцией (§10.1)
            finished = transactionManager.execute(() -> fail(userId, requestId, Code.BUSINESS_RULE_VIOLATION, outcome));
        }
        return result(finished, true);
    }

    /** Без согласия недоступно всё /ai/requests* (§6.10), в том числе история. */
    public AiRequestView get(UUID userId, UUID requestId) {
        consentService.requireConsent(userId);
        return transactionManager.execute(Propagation.SUPPORTS, () -> requests.findById(userId, requestId))
                .map(request -> AiRequestView.from(request, clock.instant()))
                .orElseThrow(() -> new NotFoundException("Запрос не найден"));
    }

    public List<AiRequestView> list(UUID userId, int limit, int offset) {
        consentService.requireConsent(userId);
        int pageSize = Math.clamp(limit, 1, MAX_PAGE_SIZE);
        int from = Math.max(offset, 0);
        return transactionManager.execute(Propagation.SUPPORTS, () -> requests.findByUser(userId, pageSize, from))
                .stream()
                .map(request -> AiRequestView.from(request, clock.instant()))
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
            return new Start(request, true, 0, List.of());
        }

        var pending = requests.findPending(userId);
        if (pending.isPresent()) {
            if (!pending.get().isStale(now, timeout)) {
                throw new AiRequestException(Code.AI_REQUEST_IN_PROGRESS);
            }
            // сервер упал во время вызова AI — иначе пользователь застрял бы навсегда (§10.2)
            markStale(pending.get());
        }

        // квота провайдера исчерпана — незачем записывать запрос и вызывать AI (§8)
        var breakerOpen = breaker.retryAfter(now);
        if (breakerOpen.isPresent()) {
            throw AiRequestException.providerQuotaExhausted(breakerOpen.get());
        }
        AiUsageQuota.Allowance allowance = usageQuota.check(userId, now);

        AiRequest request = AiRequest.start(userId, idempotencyKey, hash, messageLength, hash,
                AiContract.CONTRACT_VERSION, AiContract.PROMPT_VERSION, assistant.provider(), assistant.model(), now);
        requests.add(request);
        List<String> memory = memories.findByUser(userId, limits.memory().maxItems()).stream()
                .map(AiMemory::content)
                .toList();
        return new Start(request, false, allowance.createsLeft(), memory);
    }

    private void markStale(AiRequest request) {
        request.failed(Code.AI_TIMEOUT, new AiRequest.Usage(request.getAiCalls(), request.getInputTokens(),
                request.getOutputTokens(), (int) limits.request().requestTimeout().toMillis()));
        requests.update(request);
    }

    // ─── Вызовы AI, вне транзакции ────────────────────────────────────────────

    /** @param createsLeft остаток суточного лимита созданий: AI видит его как maxCreates, план больше — отказ */
    private Outcome converse(UUID userId, String message, Instant startedAt, int createsLeft, List<String> memory) {
        AiLimits.Plan planLimits = limits.plan().withMaxCreates(Math.min(limits.plan().maxCreates(), createsLeft));
        Duration timeout = limits.request().requestTimeout();
        Conversation conversation = new Conversation(limits.data().maxTokens());
        if (!memory.isEmpty()) {
            // память уходит провайдеру в каждом вызове — пользователь видит это в журнале прочитанного (§13)
            conversation.accessLog.add(new DataAccess(MEMORY_ACCESS, memory.size(), null));
        }
        LocalDate today = LocalDate.ofInstant(startedAt, ZoneOffset.UTC);
        Tokens tokens = new Tokens();
        List<String> violations = List.of();
        boolean repaired = false;
        int calls = 0;

        while (true) {
            Duration timeLeft = timeout.minus(Duration.between(startedAt, clock.instant()));
            if (timeLeft.isNegative() || timeLeft.isZero()) {
                return failure(Code.AI_TIMEOUT, calls, tokens, startedAt, conversation);
            }
            if (calls >= limits.request().maxCallsPerRequest()) {
                return failure(Code.AI_INVALID_RESPONSE, calls, tokens, startedAt, conversation);
            }

            AiReply reply;
            calls++;
            try {
                reply = assistant.nextTurn(new AiPrompt(AiContract.CONTRACT_VERSION, AiContract.PROMPT_VERSION,
                        LOCALE, TIMEZONE, today, message, calls, violations, timeLeft, planLimits,
                        List.copyOf(conversation.rounds), memory));
            } catch (AiProviderException ex) {
                Code code = switch (ex.getKind()) {
                    case UNAVAILABLE, RATE_LIMITED -> Code.AI_PROVIDER_UNAVAILABLE;
                    case QUOTA_EXHAUSTED -> Code.AI_PROVIDER_QUOTA_EXHAUSTED;
                    case TIMEOUT -> Code.AI_TIMEOUT;
                    // перефразировать должен пользователь, не модель
                    case REFUSED -> Code.AI_REFUSED;
                    case MALFORMED, OUTPUT_TRUNCATED -> null;
                };
                if (ex.getKind() == AiProviderException.Kind.QUOTA_EXHAUSTED) {
                    breaker.open(clock.instant());
                }
                if (code != null) {
                    return failure(code, calls, tokens, startedAt, conversation);
                }
                // неразобранный или оборванный ответ — тот же неверный ход: один шанс исправиться
                if (repaired) {
                    return failure(Code.AI_INVALID_RESPONSE, calls, tokens, startedAt, conversation);
                }
                repaired = true;
                violations = ex.getKind() == AiProviderException.Kind.OUTPUT_TRUNCATED
                        ? List.of("response: OUTPUT_TRUNCATED")
                        : ex.getViolations().isEmpty() ? List.of("response: MALFORMED") : ex.getViolations();
                continue;
            }
            tokens.add(reply);

            if (Duration.between(startedAt, clock.instant()).compareTo(timeout) > 0) {
                return failure(Code.AI_TIMEOUT, calls, tokens, startedAt, conversation);
            }

            AiTurn turn = validator.normalize(reply.turn());
            AiPlanPolicy.Verdict verdict = validator.validate(turn, today, conversation.registry);
            // исчерпав раунды, AI обязан вернуть не DataRequest (§6.3)
            if (verdict.ok() && turn instanceof AiTurn.DataRequest && conversation.rounds.size() >= limits.data().maxRounds()) {
                verdict = new AiPlanPolicy.Verdict(List.of("outcome: DATA_ROUNDS_EXHAUSTED"), false);
            }
            if (verdict.ok() && turn instanceof AiTurn.DataRequest request) {
                // раунд данных — не исправление: шанс на повтор не тратится, вызов — тратится
                DataRound round = catalog.execute(userId, conversation.rounds.size() + 1, request,
                        conversation.registry, conversation.budget);
                conversation.rounds.add(round);
                round.results().forEach(result ->
                        conversation.accessLog.add(new DataAccess(result.query(), result.items().size(), round.reason())));
                violations = List.of();
                continue;
            }
            // суточный лимит созданий: как и слишком большой план, не исправляется повтором (T-1)
            if (verdict.ok() && turn instanceof AiTurn.Proposal p && AiPlanPolicy.countCreates(p.plan()) > createsLeft) {
                return failure(Code.AI_QUOTA_EXCEEDED, calls, tokens, startedAt, conversation);
            }
            if (verdict.ok()) {
                return new Outcome(turn, null, tokens.usage(calls, startedAt, clock.instant()), conversation);
            }
            // слишком большой план не исправляется повтором — AI молча выбросил бы часть операций
            if (verdict.tooLarge()) {
                return failure(Code.AI_PLAN_TOO_LARGE, calls, tokens, startedAt, conversation);
            }
            List<String> found = verdict.violations();
            // один повтор с замечаниями; снова неверно → AI_INVALID_RESPONSE (§7.1)
            if (repaired) {
                return failure(Code.AI_INVALID_RESPONSE, calls, tokens, startedAt, conversation);
            }
            repaired = true;
            violations = found;
        }
    }

    private Outcome failure(Code code, int calls, Tokens tokens, Instant startedAt, Conversation conversation) {
        return new Outcome(null, code, tokens.usage(calls, startedAt, clock.instant()), conversation);
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

        /** Без тернарного оператора: он распаковал бы null в int. */
        private static Integer sum(Integer total, Integer value) {
            if (value == null) {
                return total;
            }
            return total == null ? value : Integer.valueOf(total + value);
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
        recordDataAccess(request, outcome.conversation());
        if (outcome.failure() != null) {
            request.failed(outcome.failure(), outcome.usage());
        } else {
            switch (outcome.turn()) {
                case AiTurn.Answer a -> request.answered(a.answer(), outcome.usage());
                case AiTurn.ClarificationNeeded c -> request.clarificationNeeded(c.clarificationQuestion(), outcome.usage());
                case AiTurn.Refused r -> request.refused(r.refusalReason(), outcome.usage());
                case AiTurn.DataRequest d -> throw new IllegalStateException("DataRequest is handled in converse");
                case AiTurn.Proposal p -> {
                    HandleRegistry registry = outcome.conversation().registry;
                    ExecuteAiPlan.Creations creations = executeAiPlan.executeCreations(userId, p.plan().create(), registry);
                    if (p.plan().hasChanges()) {
                        // карточка — после созданий: изменения могут ссылаться на новые проекты (n_*)
                        ValidatedPlan plan = previewBuilder.build(userId, p.plan(), registry, creations.refs());
                        Instant now = clock.instant();
                        request.proposed(p.summary(), p.answer(), creations.report(), plan,
                                now.plus(limits.request().planTtl()), outcome.usage());
                    } else {
                        request.executed(p.summary(), p.answer(), creations.report(), outcome.usage(), clock.instant());
                    }
                }
            }
        }
        requests.update(request);
        return request;
    }

    private AiRequest fail(UUID userId, UUID requestId, Code code, Outcome outcome) {
        AiRequest request = requests.findById(userId, requestId).orElseThrow();
        if (request.isPending()) {
            recordDataAccess(request, outcome.conversation());
            request.failed(code, outcome.usage());
            requests.update(request);
        }
        return request;
    }

    /** Журнал и дескрипторы пишутся при любом итоге: данные провайдеру уже ушли (§13). */
    private static void recordDataAccess(AiRequest request, Conversation conversation) {
        request.recordDataAccess(conversation.registry.snapshot(), conversation.accessLog, conversation.rounds.size());
    }

    // ─── Ответ ────────────────────────────────────────────────────────────────

    private Result result(AiRequest request, boolean created) {
        if (request.getStatus() == AiRequestStatus.FAILED) {
            Code code = Code.valueOf(request.getErrorCode());
            throw switch (code) {
                case AI_REFUSED -> AiRequestException.refused(
                        request.getRefusalReason() != null ? request.getRefusalReason() : RefusalReason.OTHER);
                // срок известен, только пока открыт предохранитель; срок квоты пользователя не хранится
                case AI_PROVIDER_QUOTA_EXHAUSTED ->
                        AiRequestException.providerQuotaExhausted(breaker.retryAfter(clock.instant()).orElse(null));
                default -> new AiRequestException(code);
            };
        }
        return new Result(AiRequestView.from(request, clock.instant()), created);
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
