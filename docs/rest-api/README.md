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
| `NOT_FOUND` | 404 | Маршрут или объект не найден |
| `CONFLICT` | 409 | Конфликт с текущим состоянием: email занят, устаревшая `version`, недостаточно баллов |
| `OPTIMISTIC_LOCK` | 409 | Объект изменили параллельно — перезагрузить и повторить |
| `TOO_MANY_REQUESTS` | 429 | Слишком много попыток — повторить позже |
| `DATABASE` | 503 | База данных недоступна |
| `INTERNAL_SERVER_ERROR` | 500 | Непредвиденная ошибка; подробностей в ответе нет |
