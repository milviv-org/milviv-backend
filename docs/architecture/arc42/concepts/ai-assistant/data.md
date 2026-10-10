# AI Assistant — Данные

[← AI Assistant](README.md) · Метки [Ф] [Р] [П] [Т] [TO] [Д] — см. [README](README.md).

Хранимая модель, изменения схемы БД, аудит.

---

## 6.8 Хранимая модель `AiRequest` (domain/ai)

| Поле | Назначение |
|------|------------|
| `id` UUID, `user_id` | Фильтр `user_id` во всех запросах [Ф AD-14] |
| `status` | `PENDING_AI → EXECUTED \| PROPOSED \| ANSWERED \| NEEDS_CLARIFICATION \| FAILED`; `PROPOSED → EXECUTED \| REJECTED \| EXPIRED` (касается только ожидающих изменений и удалений) |
| `idempotency_key`, `request_hash` | UNIQUE(`user_id`, `idempotency_key`) |
| `handles` JSONB | Дескриптор → (тип, ID, версия) |
| `data_access_log` JSONB | Что прочитано, без содержимого |
| `plan` JSONB | `ValidatedPlan`: операции, названия затронутых, `planFormatVersion` |
| `decision` JSONB | Подтверждённые `itemId` — **доказательство согласия** |
| `result` JSONB | Отчёт об операциях ([§6.7](user-flow.md)) |
| `answer` | Для `ANSWERED`; по желанию AI — и для `PROPOSED` |
| версии, провайдер, модель, `error_code`, вызовы, раунды, токены, длительность | Воспроизводимость и наблюдаемость |
| `created_at`, `expires_at`, `executed_at`, `version` | TTL; оптимистичная блокировка [Ф] |

Один активный запрос на пользователя — `UNIQUE(user_id) WHERE status = 'PENDING_AI'`.

---

## 6.9 Необходимые изменения схемы БД [Р G-6]

**Реализовано** в миграциях V1–V14. Итоговая схема — [database.md](../../../database.md).

| Изменение | Зачем | Поля по документации | Открыто |
|-----------|-------|----------------------|---------|
| Создать `goals` | Цели (FR-01) | `id`, `user_id` (FK, `ON DELETE CASCADE`), название, описание, `version`, `created_at`, `updated_at` | Статус цели, срок (в доке нет) |
| Создать `rewards` | Награды (FR-09) | `id`, `user_id`, название, описание, стоимость, `hidden`, `version`, даты | — |
| `users.hidden_rewards_password_hash` | Один пароль на все скрытые награды пользователя [Р G-18] | BCrypt-хеш [Ф AD-05], nullable (пока скрытых наград нет) | — |
| `tasks.goal_id` | Связь задачи с целью без проекта (FR-03) | FK → `goals`, nullable, `ON DELETE SET NULL` [Р G-15] | — |
| `projects.goal_id` | Связь проекта с целью (FR-02) | FK → `goals`, nullable, `ON DELETE SET NULL` [Р G-15] | — |
| Удалить `projects.manual_difficulty` | Сложность проекта только вычисляется [Ф пользовательская дока] | — | — |
| Создать `ai_requests` | §6.8 | — | — |
| Создать `ai_consents` | Согласие на использование данных [Р G-12] | `user_id` (PK/FK, `ON DELETE CASCADE`), `accepted_at` | Отзыв и версия текста не нужны [Р G-24] |
| Ограничение `tasks.points` | Диапазон 1..1000 [Р G-13] | `CHECK (points BETWEEN 1 AND 1000)` — минимум [П] | — |

`version` в `goals` и `rewards` нужна: без неё нельзя обнаружить, что сущность изменилась между предпросмотром и согласием ([§10](reliability.md)).

---

## 13. Audit and observability

| Данные | Где | Как долго |
|--------|-----|-----------|
| Согласие на использование данных: когда дано | `ai_consents` | Бессрочно (доказательство согласия) |
| Метаданные (версии, провайдер, модель, статус, ошибка, вызовы, раунды, токены, длительность) | `ai_requests` | Бессрочно |
| Журнал прочитанного (тип, количество) | `ai_requests` | Бессрочно |
| Согласие (`decision`) и результат по операциям | `ai_requests` | Бессрочно: доказательство, что изменение или удаление подтвердил пользователь |
| `ValidatedPlan`, `answer` | `ai_requests` | До исполнения или ответа + 30 дней |
| Каждая операция | `audit_log` [Ф V5]: `TASK_CREATED/UPDATED/DELETED…`, `details={source: "AI", requestId, fields: [...]}` — **имена** изменённых полей, без значений | По политике `audit_log`; закрывает часть R-03 |
| Ошибки | `error_log` [Ф V6] | По политике |

**Не хранить по умолчанию [П]:**
- сырое сообщение пользователя (только длина и хеш);
- `dataResults` (копия БД увеличивает поверхность утечки);
- сырые ответы AI (только `AI_DEBUG_CAPTURE` в dev, TTL 7 дней);
- тексты в логах INFO.

**Приватность:**
- удаление пользователя каскадно удаляет `ai_requests`;
- бесплатный тариф — данные могут использоваться провайдером [Р G-8, принятый риск], поэтому нужно предупреждение в браузере.
