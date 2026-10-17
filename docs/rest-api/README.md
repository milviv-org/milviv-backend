# REST API

Описание HTTP API MILVIV по модулям.

| Модуль | Файл |
|--------|------|
| Аутентификация | [auth-api.md](auth-api.md) |
| Цели | [goals-api.md](goals-api.md) |
| Проекты | [projects-api.md](projects-api.md) |
| Задачи | [tasks-api.md](tasks-api.md) |
| Планы и календарь | [planning-api.md](planning-api.md) |
| Награды и баллы | [rewards-api.md](rewards-api.md) |
| Рефлексия | [reflection-api.md](reflection-api.md) |
| Статистика | [statistics-api.md](statistics-api.md) |
| Звуки | [sounds-api.md](sounds-api.md) |
| AI-ассистент | [ai-api.md](ai-api.md) |

## Общие правила

- Формат — JSON, `Content-Type: application/json`. Даты — ISO-8601 в UTC (`2026-10-11T12:00:00Z`).
- **Доступ.** Все маршруты требуют входа, кроме помеченных «Публичный». Вход подтверждают cookie, которые сервер ставит при входе (см. [auth-api.md](auth-api.md)). Браузер отправляет их сам; клиенту нужно только не отключать cookie (`fetch(..., { credentials: 'same-origin' })`).
- Без входа или с истёкшим access-токеном — `401`. Клиент вызывает `POST /auth/refresh` и повторяет запрос.

## Ошибки

Все ошибки возвращаются в одном формате:

```json
{
  "errorCode": "VALIDATION",
  "message": "Validation failed",
  "extension": { "errors": [ { "field": "email", "message": "..." } ] },
  "timestamp": "2026-10-11T12:00:00Z"
}
```

| Поле | Описание |
|------|----------|
| `errorCode` | Код ошибки — по нему клиент решает, что делать |
| `message` | Текст для пользователя |
| `extension` | Дополнительные данные. Сейчас только `errors` для `VALIDATION`; у остальных ошибок поля нет |
| `timestamp` | Когда произошла |

| `errorCode` | HTTP | Когда |
|-------------|------|-------|
| `VALIDATION` | 400 | Поля запроса не прошли проверку; список — в `extension.errors`. Некорректный JSON — `field: "body"`; значение неверного типа (например, дата не `YYYY-MM-DD`) — имя поля |
| `UNAUTHORIZED` | 401 | Нет входа, токен недействителен, неверный email или пароль |
| `FORBIDDEN` | 403 | Нет прав на действие: неверный пароль скрытых наград или аккаунта |
| `AI_CONSENT_REQUIRED` | 403 | Нет согласия на использование данных для AI |
| `AI_REQUEST_IN_PROGRESS` | 409 | Предыдущий AI-запрос ещё обрабатывается |
| `AI_QUOTA_EXCEEDED` | 429 | Исчерпан лимит пользователя на AI; `extension.retryAfterSeconds` и `Retry-After` |
| `IDEMPOTENCY_KEY_REUSED` | 422 | `Idempotency-Key` уже использован с другим телом |
| `AI_REFUSED` | 422 | AI отказался выполнять запрос |
| `AI_PLAN_TOO_LARGE` | 422 | AI предложил больше операций, чем разрешено |
| `BUSINESS_RULE_VIOLATION` | 422 | Операции AI нарушили правило домена; ничего не изменено |
| `APPROVAL_INVALID` | 422 | Подтверждён элемент, которого нет в карточке AI |
| `TARGET_CHANGED` | 409 | Сущность изменилась после предложения AI; ничего не изменено |
| `REQUEST_STATE_CONFLICT` | 409 | AI-запрос уже решён или в нём нечего подтверждать |
| `PLAN_EXPIRED` | 410 | Предложение AI устарело |
| `AI_INVALID_RESPONSE` | 502 | Ответ AI не удалось обработать |
| `AI_PROVIDER_UNAVAILABLE` | 503 | AI временно недоступен |
| `AI_PROVIDER_QUOTA_EXHAUSTED` | 503 | Бесплатная квота AI-провайдера исчерпана |
| `AI_TIMEOUT` | 504 | AI не ответил вовремя |
| `NOT_FOUND` | 404 | Маршрут или объект не найден |
| `CONFLICT` | 409 | Конфликт с текущим состоянием: email занят, устаревшая `version`, недостаточно баллов |
| `OPTIMISTIC_LOCK` | 409 | Объект изменили параллельно — перезагрузить и повторить |
| `TOO_MANY_REQUESTS` | 429 | Слишком много попыток — повторить позже |
| `DATABASE` | 503 | База данных недоступна |
| `INTERNAL_SERVER_ERROR` | 500 | Непредвиденная ошибка; подробностей в ответе нет |
