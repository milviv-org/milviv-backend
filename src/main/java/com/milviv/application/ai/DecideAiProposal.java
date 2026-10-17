package com.milviv.application.ai;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.milviv.application.transaction.TransactionManager;
import com.milviv.domain.ai.AiDecision;
import com.milviv.domain.ai.AiRequest;
import com.milviv.domain.ai.AiRequestRepository;
import com.milviv.domain.ai.AiRequestStatus;
import com.milviv.domain.ai.OperationReport;
import com.milviv.domain.ai.PendingOperation;
import com.milviv.domain.exception.AiRequestException;
import com.milviv.domain.exception.AiRequestException.Code;
import com.milviv.domain.exception.DomainException;
import com.milviv.domain.exception.NotFoundException;
import com.milviv.domain.exception.OptimisticLockException;
import com.milviv.domain.exception.ValidationException;

/**
 * Фаза 2 AI-запроса (§6.7): пользователь решает по карточке.
 *
 * - исполняются только отмеченные изменения и удаления (G-3), остальные — CANCELLED;
 * - одна транзакция — всё или ничего: ошибка → ничего не изменено, запрос остаётся PROPOSED (§10.1);
 * - строка запроса заблокирована: два решения по одной карточке идут по очереди;
 * - повтор того же решения → тот же ответ, другое решение → REQUEST_STATE_CONFLICT.
 */
public class DecideAiProposal {

    private final AiConsentService consentService;
    private final ExecuteAiPlan executeAiPlan;
    private final AiRequestRepository requests;
    private final TransactionManager transactionManager;
    private final Clock clock;

    public DecideAiProposal(AiConsentService consentService, ExecuteAiPlan executeAiPlan, AiRequestRepository requests,
                            TransactionManager transactionManager, Clock clock) {
        this.consentService = consentService;
        this.executeAiPlan = executeAiPlan;
        this.requests = requests;
        this.transactionManager = transactionManager;
        this.clock = clock;
    }

    /** @param approvedIds itemId из карточки; пусто — отклонить всё */
    public AiRequestView execute(UUID userId, UUID requestId, List<String> approvedIds) {
        if (approvedIds == null || approvedIds.stream().anyMatch(id -> id == null)) {
            throw new ValidationException("approvedChangeIds", "Обязательное поле: список подтверждённых itemId");
        }
        return decide(userId, requestId, new LinkedHashSet<>(approvedIds));
    }

    /** Уже созданное остаётся — удалить его можно вручную или новым запросом (§6.7). */
    public AiRequestView reject(UUID userId, UUID requestId) {
        return decide(userId, requestId, Set.of());
    }

    /** Итог транзакции: истёкший запрос помечается EXPIRED, а ошибка бросается после коммита. */
    private record Decision(AiRequest request, boolean expired) {}

    private AiRequestView decide(UUID userId, UUID requestId, Set<String> approved) {
        consentService.requireConsent(userId);
        Decision decision = transactionManager.execute(() -> {
            AiRequest request = requests.findByIdForUpdate(userId, requestId)
                    .orElseThrow(() -> new NotFoundException("Запрос не найден"));
            Instant now = clock.instant();

            if (request.getDecision() != null) {
                if (Set.copyOf(request.getDecision().approved()).equals(approved)) {
                    return new Decision(request, false);
                }
                throw new AiRequestException(Code.REQUEST_STATE_CONFLICT);
            }
            if (request.getStatus() == AiRequestStatus.EXPIRED) {
                throw new AiRequestException(Code.PLAN_EXPIRED);
            }
            if (request.getStatus() != AiRequestStatus.PROPOSED) {
                throw new AiRequestException(Code.REQUEST_STATE_CONFLICT);
            }
            if (request.isExpired(now)) {
                request.expire();
                requests.update(request);
                return new Decision(request, true);
            }

            List<String> itemIds = request.getPlan().operations().stream().map(PendingOperation::itemId).toList();
            if (!itemIds.containsAll(approved)) {
                throw new AiRequestException(Code.APPROVAL_INVALID);
            }

            List<OperationReport.Item> report = new ArrayList<>(request.getReport() == null
                    ? List.of() : request.getReport().items());
            report.addAll(changes(userId, request, approved));
            // порядок карточки, а не порядок в теле запроса
            List<String> approvedInOrder = itemIds.stream().filter(approved::contains).toList();
            request.decided(new AiDecision(approvedInOrder, now), new OperationReport(report), now);
            requests.update(request);
            return new Decision(request, false);
        });
        if (decision.expired()) {
            throw new AiRequestException(Code.PLAN_EXPIRED);
        }
        return AiRequestView.from(decision.request(), clock.instant());
    }

    private List<OperationReport.Item> changes(UUID userId, AiRequest request, Set<String> approved) {
        try {
            return executeAiPlan.executeChanges(userId, request.getPlan(), approved);
        } catch (AiRequestException | OptimisticLockException ex) {
            // TARGET_CHANGED — как есть; OptimisticLock — повторит RetryableTransactionManager
            throw ex;
        } catch (DomainException ex) {
            // итог нарушает правило домена — например, подтверждено не всё (§6.7)
            throw AiRequestException.businessRule(ex.getMessage());
        }
    }
}
