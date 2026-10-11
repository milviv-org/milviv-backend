# AI Assistant — Реализация по подэтапам

[← AI Assistant](README.md)

Этап 9 делится на подэтапы. Каждый **сначала проектируется** здесь (классы, схема связей, решения на подтверждение), затем реализуется. После реализации раздел подэтапа сверяется с кодом.

**Статусы:** ✅ реализовано · 📐 спроектировано, ждёт подтверждения · ⏳ не начато.

| # | Подэтап | Что появляется | Статус |
|---|---------|----------------|--------|
| 9.1 | Согласие и конфиг | `ai_consents`, `GET/POST /ai/consent`, проверка согласия, `AiConfig` со всеми лимитами [§8](limits-and-security.md) | ✅ |
| 9.2 | Каркас запроса | `AiRequest` (состояния, идемпотентность, один активный запрос), `AiAssistantPort` + `FakeAiAssistant`, `HandleAiRequest` для исходов `ANSWER` / `CLARIFICATION_NEEDED` / `REFUSED`, `POST /ai/requests`, `GET /ai/requests/{id}`, зависшие `PENDING_AI` | ✅ |
| 9.3 | Создания | Создающая часть `PlanDraft`, `AiResponseValidator` + `AiPolicy`, `ExecuteAiPlan` (фаза 1 через ручные сценарии, одна транзакция), `OperationReportBuilder` | ⏳ |
| 9.4 | Каталог данных | `AiDataCatalog` (6 запросов, проекции, урезание, без скрытых наград), `HandleRegistry` (дескрипторы с версией), цикл ≤ 2 раундов, журнал прочитанного, создание внутри существующих целей и проектов | ⏳ |
| 9.5 | Изменения и удаления | Изменения и удаления в плане, `PreviewBuilder`, `PROPOSED`, `POST /ai/requests/{id}/execution` и `/rejection`, сверка версий (`TARGET_CHANGED`), правила дат на итоговом состоянии, TTL | ⏳ |
| 9.6 | Адаптер Gemini | Провайдерские ключи `AiConfig`, JDK `HttpClient`, промпт `assistant@1`, схема ответа, строгий разбор, таймауты, ретраи, `AiFailure`; тесты на HTTP-заглушке; онлайн-прогон ~25 сообщений (п. 10 [§17](README.md#17-final-architecture)) | ⏳ |
| 9.7 | Квоты и предохранитель | Лимиты на пользователя (сутки, минута, созданий в сутки), предохранитель квоты провайдера | ⏳ |
| 9.8 | Память AI | `ai_memories`. В концепции не описана — только в пользовательской доке; проектируется отдельно | ⏳ |

Порядок — из [§17](README.md#17-final-architecture): пункты 1–3 (аутентификация, миграции, ручные CRUD) сделаны этапами 1–6; пункт 5 разбит на 9.2 и 9.3; пункт 10 (онлайн-прогон) входит в 9.6. До 9.6 настоящего провайдера нет: всё проверяется на `FakeAiAssistant`.

### Расхождения концепции с кодом — учесть в подэтапах

Концепция писалась до этапов 1–8. Что в коде устроено иначе и где это всплывёт:

| # | В концепции | В коде | Подэтап | Что делать |
|---|-------------|--------|---------|------------|
| K-1 | Пути `/api/ai/...` | Префикса `/api` нет ни у одного модуля | 9.1 | `/ai/...` — решение 1 ниже |
| K-2 | `AiConfig` передаётся в `AiPolicy`, `AiDataCatalog` | `application` не может импортировать `infrastructure` | 9.1 | `AiConfig` → `AiLimits` (application), как `AuthConfig` → `AuthPolicy` |
| K-3 | Ключи с дефолтами — в `application.properties` | Там только БД, сервер, логи; ключи `AUTH_*`, `CALENDAR_*` и др. — только в `.env.example` | 9.1 | Записать ключи `ai.*` в `application.properties` (по §8); остальные модули — отдельно, по желанию |
| K-4 | Пользователь и AI работают в часовом поясе (`timezone` в ходе 1) | Часового пояса пользователя нет; все даты — UTC | 9.2 | Передавать `UTC`, пока часовой пояс не появится |
| K-5 | ID в отчёте — числа (`"id": 89`) | ID — UUID | 9.3 | В отчёте UUID |
| K-6 | `userId` из `rest/filters` (D-01) | Аутентификация в `RouterScanner` + `JwtCookieAuthenticator` | — | Только текст концепции |
| K-7 | Каждая операция → `audit_log` (§13) | `audit_log` никто не пишет (R-03) | 9.3 | Решить: писать журнал с этого подэтапа или отложить |
| K-8 | `LIST_TASKS(unassigned?, period?)`, `LIST_REFLECTIONS(task?, period?)` | В `TaskFilter` нет «без проекта и цели» и периода; ответы рефлексии — без фильтров | 9.4 | Расширить репозитории |
| K-9 | Правила дат — на итоговом состоянии плана | Ручные сценарии проверяют каждую операцию отдельно | 9.5 | Порядок исполнения: сначала проекты, чей срок растёт, затем задачи, затем проекты, чей срок уменьшается. Так любое промежуточное состояние допустимо, если допустимо итоговое |
| K-10 | Интеграционные тесты — Testcontainers + PostgreSQL | Docker нет, тесты на H2 | все | H2; тест на PostgreSQL пишется, но пропускается без Docker |
| K-11 | Новой задаче ничего не назначается | Ручной `TaskCommands.create` назначает стандартные вопросы рефлексии (этап 6) | 9.3 | Задачи от AI получат их тоже — следствие принципа 5 |

---

## 9.1 Согласие и конфиг ✅

**Задача.** Без согласия на использование данных AI недоступен [Р G-12]. Согласие даётся один раз, отзыва нет [Р G-24]. Все лимиты AI — в одном конфиге с дефолтами [§8](limits-and-security.md).

**В подэтап не входит:** вызов AI. `requireConsent` появляется сейчас, а вызывать его будет `HandleAiRequest` из 9.2.

### Классы

| Класс | Слой | Ответственность |
|-------|------|-----------------|
| `AiConsentController` | presentation | `GET /ai/consent`, `POST /ai/consent` |
| `AiConsentService` | application/ai | Узнать статус, дать согласие, проверить согласие перед вызовом AI |
| `ConsentView`, `AcceptResult` | application/ai | Ответ: `accepted`, `acceptedAt`; признак «согласие записано сейчас» |
| `AiLimits` | application/ai | Типизированные лимиты: `Request`, `Data`, `Plan` — вложенные records |
| `AiConsentRepository` | domain/ai | Интерфейс хранилища согласий |
| `AiConsentRequiredException` | domain/exception | `403 AI_CONSENT_REQUIRED` |
| `AiConsentJdbcRepository` | infrastructure/persistence | Таблица `ai_consents` (V14) |
| `AiConfig` | infrastructure/config | Читает `AI_*` / `ai.*` один раз при старте, проверяет, выдаёт `AiLimits` (K-2) |
| `HttpStatusResolver` | infrastructure/http | Новый код `AI_CONSENT_REQUIRED` → 403 |

### Схема связей

```mermaid
classDiagram
    direction LR

    class AiConsentController {
        +status(ctx)
        +accept(ctx)
    }
    class AiConsentService {
        +status(userId) ConsentView
        +accept(userId) AcceptResult
        +requireConsent(userId)
    }
    class AiConsentRepository {
        <<interface>>
        +findAcceptedAt(userId) Optional~Instant~
        +accept(userId, at) boolean
    }
    class AiConsentJdbcRepository
    class AiConsentRequiredException
    class TransactionManager {
        <<interface>>
    }
    class AiConfig {
        +toLimits() AiLimits
    }
    class AiLimits {
        <<record>>
        Request request
        Data data
        Plan plan
    }
    class ConfigLoader
    class HandleAiRequest {
        <<9.2>>
    }

    AiConsentController --> AiConsentService
    AiConsentService --> AiConsentRepository
    AiConsentService --> TransactionManager
    AiConsentService ..> AiConsentRequiredException : бросает
    AiConsentJdbcRepository ..|> AiConsentRepository
    AiConfig --> ConfigLoader
    AiConfig ..> AiLimits : создаёт
    HandleAiRequest ..> AiConsentService : requireConsent
    HandleAiRequest ..> AiLimits
```

### Как работает

- `POST /ai/consent` записывает `accepted_at`, если согласия ещё нет. Повтор ничего не меняет и возвращает исходную дату. Одновременные запросы одного пользователя идут по очереди: перед вставкой блокируется его строка в `users`.
- `requireConsent(userId)` — одна точка проверки для всех будущих `/ai/requests*`. Нет согласия → `AiConsentRequiredException` до любого обращения к провайдеру.
- `AiConfig` по образцу `AuthConfig`: ключ env `AI_*`, property `ai.*`, дефолт в коде, проверка при старте (положительные, `min ≤ max`). Наружу отдаёт только `AiLimits` — application не импортирует infrastructure.

**Состав `AiLimits`** (дефолты — из [§8](limits-and-security.md)):

| Группа | Значения |
|--------|----------|
| `Request` | длина сообщения 2000; запросов 20 в сутки и 3 в минуту; вызовов AI на запрос 4; таймаут запроса 90 с; TTL плана 24 ч; созданий в сутки 200; предохранитель 60 мин |
| `Data` | раундов 2; запросов за раунд 3; элементов 100; длина текста 1000; токенов данных 16000 |
| `Plan` | созданий 40, изменений 20, удалений 10; проектов 10, задач в узле 10, задач всего 30, наград 3; title 100, description 1000, summary 500, answer 2000; горизонт дат 2 года; стоимость награды 100 000 |

Провайдерские ключи (`AI_PROVIDER`, `AI_MODEL`, `GEMINI_API_KEY`, таймауты HTTP, ретраи, размер ответа, `maxOutputTokens`) добавляются в 9.6 вместе с адаптером. Это **отступление от §8**, где они в одной таблице с остальными: так приложение запускается без ключа Gemini, пока адаптера нет.

Ключи `ai.*` с дефолтами записываются в `application.properties`, пример — в `.env.example` (K-3).

### API

```http
GET  /ai/consent   → 200 { "accepted": false, "acceptedAt": null }
POST /ai/consent   → 201 { "accepted": true, "acceptedAt": "2026-10-11T12:00:00Z" }   (повтор → 200, та же дата)
```

### Решения на подтверждение

1. **Путь `/ai/...`, а не `/api/ai/...`**, как в концепции: у остальных модулей префикса `/api` нет (K-1).
2. **Повтор `POST /ai/consent` → 200** с исходной датой (первый → 201).
3. **`AiConfig` сразу со всеми лимитами**, кроме провайдерских. Часть значений до 9.3–9.7 не используется, зато лимиты в одном месте с первого дня, как требует [§8](limits-and-security.md).

### Сверка с концепцией

| Требование концепции | Где | В проекте |
|----------------------|-----|-----------|
| Компоненты `AiConsentService` + репозиторий, `AiConsentController` | [§4.2](architecture.md), [§17](README.md#17-final-architecture) | ✅ |
| Согласие один раз, без отзыва и версии текста | G-12, G-24 | ✅ |
| `GET` → `accepted`, `acceptedAt`; `POST` → 201 | [§6.10](user-flow.md) | ✅ + повтор → 200 (решение 2) |
| Проверка на сервере, до вызова провайдера, `403 AI_CONSENT_REQUIRED` | [§6.10](user-flow.md), [§11](reliability.md), инвариант 5 | ✅ точка проверки готова, вызов — в 9.2 |
| Согласие хранится бессрочно; удаляется вместе с пользователем | [§13](data.md) | ✅ `ON DELETE CASCADE` из V14 |
| Все лимиты в `AiConfig` с дефолтами, проверка при старте | [§8](limits-and-security.md) | ✅ кроме провайдерских — в 9.6 |
| Новые коды — в `HttpStatusResolver` | [§11](reliability.md) | ✅ |
| Тест: каждый `errorCode` есть в `HttpStatusResolver` | [§12](development.md) | ✅ для `AI_CONSENT_REQUIRED` |
| Текст окна согласия (что читается, что данные уходят Google, бесплатный тариф) | [§6.10](user-flow.md) | — задача фронтенда, сервер текст не хранит |

### Сверка с кодом (после реализации)

- Классы и связи — как в схеме. API — [ai-api.md](../../../../rest-api/ai-api.md).
- Решения 1–3 приняты.
- **Уточнение:** «уже согласен» при одновременных запросах — не перехват нарушения ключа (в PostgreSQL ошибка прерывает транзакцию), а блокировка строки пользователя перед вставкой.
- **Дополнительные проверки `AiConfig`:** запросов в минуту ≤ в сутки; задач в узле ≤ задач всего; задач, проектов и наград ≤ созданий в плане; созданий в плане ≤ созданий в сутки.
- Ключи `ai.*` записаны в `application.properties`, закомментированный пример — в `.env.example` (K-3 для AI закрыт).

### Тесты

- Сервис: без согласия → статус `accepted: false`, `requireConsent` бросает; после `accept` — проходит; повтор `accept` не меняет дату.
- H2: запись и чтение; изоляция пользователей; повторная вставка не падает.
- `AiConfig`: дефолты; переопределение; отказ при нуле, отрицательном значении, `min > max`.
- `HttpStatusResolver`: `AI_CONSENT_REQUIRED` → 403.

---

## 9.2 Каркас запроса ✅

**Задача.** Пользователь отправляет сообщение → сервер проверяет согласие и повтор → спрашивает AI вне транзакции → сохраняет итог. В этом подэтапе AI умеет только **ответить текстом**, **задать уточняющий вопрос** или **отказаться**. Запросы данных, планы и исполнение — в 9.3–9.5.

**В подэтап не входит:** квоты на пользователя и предохранитель (9.7), настоящий провайдер (9.6), JSONB-поля `handles`, `plan`, `decision`, `result`, `data_access_log` (9.3–9.5).

### Классы

| Класс | Слой | Ответственность |
|-------|------|-----------------|
| `AiRequestController` | presentation | `POST /ai/requests` (заголовок `Idempotency-Key`), `GET /ai/requests`, `GET /ai/requests/{id}` |
| `HandleAiRequest` | application/ai | Фаза 1: согласие → повтор и активный запрос → цикл вызовов AI → проверка хода → итог |
| `AiRequestView` | application/ai | Ответ клиенту: `requestId`, `status`, `answer`, `clarificationQuestion`, `errorCode`, `createdAt` |
| `AiAssistantPort` | application/ai | Интерфейс провайдера: `provider()`, `model()`, `nextTurn(AiPrompt)` |
| `AiPrompt` | application/ai | Что уходит AI: версии контракта и промпта, `today`, `timezone`, `locale`, сообщение, номер вызова, замечания к прошлому ходу |
| `AiTurn` | application/ai/contract | sealed: `Answer`, `ClarificationNeeded`, `Refused`. В 9.3–9.4 добавятся `Proposal`, `DataRequest` |
| `AiReply` | application/ai | Ход + расход токенов |
| `AiProviderException` | application/ai | Сбой провайдера: `UNAVAILABLE`, `TIMEOUT`, `MALFORMED` (полный `AiFailure` — в 9.6) |
| `AiTurnValidator` | application/ai | Длины и пустые поля хода → список замечаний |
| `UnavailableAiAssistant` | infrastructure/ai | Провайдер до 9.6: всегда `UNAVAILABLE` → `503` |
| `AiRequest` | domain/ai | Агрегат: статус, хеши, версии, счётчики, ответ; переходы состояний |
| `AiRequestStatus` | domain/ai | 8 статусов из V14 |
| `AiRequestRepository` | domain/ai | Хранение с фильтром `user_id` |
| `AiRequestException` | domain/exception | Ошибки AI с кодом из [§11](reliability.md) |
| `AiRequestJdbcRepository` | infrastructure/persistence | Таблица `ai_requests` |
| `FakeAiAssistant` | test | Заранее заданная очередь ходов или сбоев |

### Схема связей

```mermaid
classDiagram
    direction LR

    class AiRequestController {
        +create(ctx)
        +list(ctx)
        +get(ctx)
    }
    class HandleAiRequest {
        +execute(userId, idempotencyKey, message) Result
        +get(userId, requestId) AiRequestView
        +list(userId, limit, offset)
    }
    class AiConsentService {
        +requireConsent(userId)
    }
    class AiLimits {
        <<record>>
    }
    class AiAssistantPort {
        <<interface>>
        +provider() String
        +model() String
        +nextTurn(prompt) AiReply
    }
    class AiTurn {
        <<sealed>>
    }
    class Answer
    class ClarificationNeeded
    class Refused
    class AiTurnValidator {
        +validate(turn) List~String~
    }
    class AiRequest {
        +start(...)
        +answered(text, usage)
        +clarificationNeeded(text, usage)
        +refused(reason, usage)
        +failed(code, usage)
        +isStale(now, timeout) boolean
    }
    class AiRequestRepository {
        <<interface>>
    }
    class AiRequestJdbcRepository
    class UnavailableAiAssistant
    class FakeAiAssistant {
        <<test>>
    }

    AiRequestController --> HandleAiRequest
    HandleAiRequest --> AiConsentService
    HandleAiRequest --> AiLimits
    HandleAiRequest --> AiAssistantPort
    HandleAiRequest --> AiTurnValidator
    HandleAiRequest --> AiRequestRepository
    HandleAiRequest ..> AiRequest : создаёт и меняет
    AiAssistantPort ..> AiTurn : возвращает
    Answer --|> AiTurn
    ClarificationNeeded --|> AiTurn
    Refused --|> AiTurn
    AiRequestJdbcRepository ..|> AiRequestRepository
    UnavailableAiAssistant ..|> AiAssistantPort
    FakeAiAssistant ..|> AiAssistantPort
```

### Состояния в 9.2

```mermaid
stateDiagram-v2
    [*] --> PENDING_AI : POST /ai/requests
    PENDING_AI --> ANSWERED : Answer
    PENDING_AI --> NEEDS_CLARIFICATION : ClarificationNeeded
    PENDING_AI --> FAILED : Refused, сбой провайдера, неверный ход, таймаут
    PENDING_AI --> FAILED : завис дольше таймаута запроса
```

`EXECUTED`, `PROPOSED`, `REJECTED`, `EXPIRED` появятся в 9.3 и 9.5.

### Как работает `POST /ai/requests`

1. `requireConsent` → иначе `403 AI_CONSENT_REQUIRED`, ничего не записано.
2. Сообщение: не пустое, ≤ `messageMaxLength` → иначе `400`.
3. **Транзакция 1** (короткая):
   1. блокируется строка пользователя — запросы одного пользователя идут по очереди (тот же приём, что в 9.1);
   2. есть запрос с этим `Idempotency-Key`:
      - хеш тела другой → `422 IDEMPOTENCY_KEY_REUSED`;
      - `PENDING_AI` → `409 AI_REQUEST_IN_PROGRESS`;
      - завершён → **повтор**: тот же ответ, AI не вызывается;
   3. есть другой `PENDING_AI`: завис (старше `requestTimeout`) → `FAILED AI_TIMEOUT`; иначе → `409 AI_REQUEST_IN_PROGRESS`;
   4. новый `AiRequest` в `PENDING_AI`.
4. **Вне транзакции** — вызовы AI (AD-08), не больше `maxCallsPerRequest`:
   - ход прошёл `AiTurnValidator` → итог;
   - ход не прошёл → ещё один вызов с замечаниями; не прошёл снова → `FAILED AI_INVALID_RESPONSE` ([§7.1](user-flow.md));
   - `DataRequest` или `Proposal` в 9.2 невозможны: в `AiTurn` их ещё нет;
   - сбой провайдера → `FAILED` с кодом; истёк `requestTimeout` → `FAILED AI_TIMEOUT`.
5. **Транзакция 2:** итог записывается условным `UPDATE … WHERE status = 'PENDING_AI' AND version = ?`.
6. Ответ: `ANSWERED` / `NEEDS_CLARIFICATION` → `201`; повтор → `200`; `FAILED` → ошибка с кодом запроса (повтор возвращает ту же ошибку).

Сырое сообщение не хранится: только длина и SHA-256 ([§13](data.md)).

### API

```http
POST /ai/requests
Idempotency-Key: 5b0c2c0e-…
{ "message": "Какие задачи я чаще всего просрочиваю?" }

→ 201 { "requestId": "7f9e…", "status": "ANSWERED", "answer": "…", "clarificationQuestion": null,
        "errorCode": null, "createdAt": "…" }

GET /ai/requests?limit=&offset=   → 200 { "items": [ … ] }   новые первыми
GET /ai/requests/{id}             → 200 …                    чужой → 404
```

| Код | HTTP | Когда |
|-----|------|-------|
| `AI_REQUEST_IN_PROGRESS` | 409 | Уже идёт другой запрос или этот же |
| `IDEMPOTENCY_KEY_REUSED` | 422 | Ключ уже использован с другим сообщением |
| `AI_PROVIDER_UNAVAILABLE` | 503 | Провайдер недоступен — до 9.6 всегда |
| `AI_TIMEOUT` | 504 | AI не ответил за `requestTimeout` |
| `AI_INVALID_RESPONSE` | 502 | Ход дважды не прошёл проверку |
| `AI_REFUSED` | 422 | AI отказался: `OFF_TOPIC`, `UNSUPPORTED_OPERATION`, `OTHER` |

### Решения на подтверждение

1. **Миграция V16:** колонки `clarification_question TEXT` и `refusal_reason VARCHAR(30)` в `ai_requests`. В V14 для них места нет: статуса `REFUSED` нет, а `answer` по концепции — только для ответа. Отказ хранится как `FAILED` + `error_code = AI_REFUSED` + `refusal_reason` — так он остаётся ошибкой `422`, как в [§11](reliability.md). Альтернатива без миграции — класть вопрос в `answer`, а причину отказа — в `error_code` (`AI_REFUSED_OFF_TOPIC`), но это смешивает смыслы колонок.
2. **Зависшие `PENDING_AI` — в 9.2, а не в 9.7.** Если сервер упадёт во время вызова AI, запрос останется `PENDING_AI`, и пользователь больше никогда не сможет отправить новый: мешает правило «один активный запрос».
3. **До 9.6 в рабочем приложении стоит `UnavailableAiAssistant`.** `POST /ai/requests` проверяет согласие, записывает запрос и отвечает `503 AI_PROVIDER_UNAVAILABLE`. Всё остальное проверяется тестами на `FakeAiAssistant`.
4. **Новые лимиты в `AiLimits`:** `clarificationMaxLength` = 300 и `dataRequestReasonMaxLength` = 200 из [§6.2](contract.md) — в 9.1 я их пропустил.
5. **`GET /ai/requests` — история запросов.** В концепции явного списка нет, но [§6.7](user-flow.md) говорит о «повторном открытии истории».
6. **`Idempotency-Key` обязателен:** 1–100 символов, латиница, цифры, `-`, `_`. Без него → `400`.
7. **`timezone` = `UTC`, `locale` = `ru-RU`** в `AiPrompt` (K-4).
8. **Jetty на виртуальных потоках:** фаза 1 держит HTTP-поток до 90 с ([§5](architecture.md), TO-12). Jetty 11.0.26 это поддерживает (`QueuedThreadPool.setVirtualThreadsExecutor`); включаю для всего сервера.

### Сверка с кодом (после реализации)

- Классы, связи и состояния — как в схемах. API — [ai-api.md](../../../../rest-api/ai-api.md). Решения 1–8 приняты; V16 — `V16__ai_request_outcome.sql`.
- **Согласие проверяется и в `GET /ai/requests*`**, а не только в `POST`: концепция закрывает без согласия все `/ai/requests*` ([§6.10](user-flow.md)).
- **Транзакция 3 перечитывает запрос.** Пока шёл вызов AI, его могли признать зависшим — тогда итог AI не записывается, остаётся уже сохранённый `FAILED`. Без перечитывания `RetryableTransactionManager` повторял бы обновление устаревшего объекта.
- **`MALFORMED` от провайдера** считается неверным ходом: один повтор с замечанием `response: MALFORMED`.
- **`FakeAiAssistant`** — в тестах (`unit/application/ai`), умеет действие «во время вызова»: сдвинуть часы или отправить параллельный запрос.
- **Не проверено:** запуск сервера на виртуальных потоках — приложение целиком не запускалось; частичный уникальный индекс V14 на H2 не воспроизводится (защищает блокировка пользователя).

### Тесты

- `AiRequest`: переходы только из `PENDING_AI`; зависший определяется по `createdAt` и таймауту.
- `AiTurnValidator`: пустой и слишком длинный ответ, вопрос, неизвестная причина отказа.
- `HandleAiRequest` на H2 + `FakeAiAssistant`:
  - без согласия → `403`, провайдер не вызван, запрос не записан;
  - `Answer` → `ANSWERED`; `ClarificationNeeded` → `NEEDS_CLARIFICATION`; `Refused` → `422 AI_REFUSED` и `FAILED`;
  - повтор с тем же ключом → тот же результат, провайдер вызван один раз; с другим телом → `422`;
  - второй запрос, пока первый `PENDING_AI` → `409`; зависший → `FAILED`, новый проходит;
  - неверный ход → повтор с замечаниями → успех; дважды неверный → `502`; сбой провайдера → `503`;
  - превышение `maxCallsPerRequest` и `requestTimeout`;
  - чужой запрос → `404`; в БД нет текста сообщения.
- `HttpStatusResolver`: все новые коды.
