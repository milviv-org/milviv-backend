# Auth API

Регистрация, вход, обновление сессии, выход. Общие правила и формат ошибок — [README.md](README.md). Устройство токенов — [concepts/authentication.md](../architecture/arc42/concepts/authentication.md).

| Метод | Путь | Доступ | Назначение |
|-------|------|--------|------------|
| POST | [/auth/register](#post-authregister) | Публичный | Регистрация и вход |
| POST | [/auth/login](#post-authlogin) | Публичный | Вход |
| POST | [/auth/refresh](#post-authrefresh) | Публичный | Новая пара токенов |
| POST | [/auth/logout](#post-authlogout) | Публичный | Выход |
| GET | [/auth/me](#get-authme) | Вход | Текущий пользователь |

## Cookie

Токены передаются только в cookie, в теле ответа их нет. JavaScript их не видит — так и задумано.

| Cookie | Содержит | Path | Живёт |
|--------|----------|------|-------|
| `__Host-access_token` | Access-токен (JWT) | `/` | 15 минут |
| `__Secure-refresh_token` | Refresh-токен | `/auth` | 30 дней, продлевается при обновлении; не дольше 90 дней от входа |

Атрибуты обеих: `HttpOnly; Secure; SameSite=Strict`. Если `AUTH_COOKIE_SECURE=false`, имена без префиксов (`access_token`, `refresh_token`) и без `Secure`.

---

## POST /auth/register

Создаёт пользователя и сразу выполняет вход.

**Тело:**

| Поле | Тип | Правила |
|------|-----|---------|
| `email` | string | Обязателен, email, до 255 символов. Регистр не важен |
| `password` | string | Обязателен, 8–64 символа и не больше 72 байт в UTF-8 |
| `name` | string | Обязателен, до 100 символов |

```json
{ "email": "abay@example.com", "password": "secret-pass", "name": "Абай" }
```

**Ответ `201`** + cookie:

```json
{
  "user": { "id": "3f1c…", "email": "abay@example.com", "name": "Абай" },
  "accessTokenExpiresAt": "2026-10-11T12:15:00Z"
}
```

| Ошибка | Когда |
|--------|-------|
| 400 `VALIDATION` | Поля не прошли проверку |
| 409 `CONFLICT` | Email уже зарегистрирован |

## POST /auth/login

**Тело:**

| Поле | Тип | Правила |
|------|-----|---------|
| `email` | string | Обязателен, email, до 255 символов |
| `password` | string | Обязателен, до 64 символов |

**Ответ `200`** + cookie — как у `/auth/register`.

| Ошибка | Когда |
|--------|-------|
| 400 `VALIDATION` | Поля не прошли проверку |
| 401 `UNAUTHORIZED` | «Неверный email или пароль» — одинаково для неизвестного email и неверного пароля |
| 429 `TOO_MANY_REQUESTS` | 5 неудачных попыток на email за 15 минут; повторить позже |

## POST /auth/refresh

Выдаёт новую пару токенов по refresh-cookie. Тела нет. Старый refresh-токен перестаёт действовать.

**Ответ `200`** + новые cookie:

```json
{ "accessTokenExpiresAt": "2026-10-11T12:30:00Z" }
```

| Ошибка | Когда |
|--------|-------|
| 401 `UNAUTHORIZED` | Cookie нет, токен истёк, сессия отозвана или токен уже использован. Cookie удаляются — нужен новый вход |

Вызывать заранее (по `accessTokenExpiresAt`) или после `401` на другом запросе. **Не вызывать параллельно** (например, из двух вкладок): второй запрос придёт со старым токеном, сервер сочтёт его украденным и завершит сессию.

## POST /auth/logout

Завершает сессию и удаляет cookie. Тела нет. Работает и с истёкшим access-токеном; повторный вызов безопасен.

**Ответ `204`** без тела.

## GET /auth/me

**Ответ `200`:**

```json
{ "id": "3f1c…", "email": "abay@example.com", "name": "Абай" }
```

| Ошибка | Когда |
|--------|-------|
| 401 `UNAUTHORIZED` | Нет входа или access-токен истёк |
