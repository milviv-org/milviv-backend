# База данных

Схема PostgreSQL. Создаётся миграциями Flyway: `src/main/resources/db/migration`.

## Общие правила

- Сущности (цели, проекты, задачи и т. д.) — ключ `id UUID`, генерирует приложение.
- Журналы (история, логи) — ключ `id BIGSERIAL`, записи только добавляются.
- У каждой пользовательской таблицы есть `user_id`. Все запросы фильтруются по нему (AD-14). При удалении пользователя его данные удаляются (`ON DELETE CASCADE`).
- `version` — номер версии для оптимистичной блокировки (AD-06).
- Сложность: `NOVICE`, `APPRENTICE`, `ADEPT`, `EXPERT`, `MASTER`, `LEGENDARY`.

## Таблицы

| Таблица | Назначение | Миграция |
|---------|------------|----------|
| [users](#users) | Пользователи | V1, V8, V15 |
| [refresh_tokens](#refresh_tokens) | Сессии входа | V15 |
| [goals](#goals) | Цели | V9 |
| [projects](#projects) | Проекты | V3, V8, V9 |
| [tasks](#tasks) | Задачи | V2, V8, V9 |
| [plans](#plans) | Планы на дату или по дням недели | V4 |
| [rewards](#rewards) | Награды | V10 |
| [points_history](#points_history) | Начисления и списания баллов | V7, V10 |
| [task_completions](#task_completions) | Факты выполнения задач | V11 |
| [task_reschedules](#task_reschedules) | Переносы задач | V11 |
| [reflection_questions](#reflection_questions) | Вопросы рефлексии | V12 |
| [task_reflection_questions](#task_reflection_questions) | Какие вопросы задать по задаче | V12 |
| [reflection_answers](#reflection_answers) | Ответы рефлексии | V12 |
| [user_sound_settings](#user_sound_settings) | Звуки событий | V13 |
| [ai_consents](#ai_consents) | Согласие на использование данных AI | V14 |
| [ai_requests](#ai_requests) | Запросы к AI | V14 |
| [ai_memories](#ai_memories) | Память AI | V14 |
| [audit_log](#audit_log) | Журнал действий | V5 |
| [error_log](#error_log) | Журнал ошибок | V6 |

---

### users

Пользователь. Входит по email и паролю. Баланс баллов здесь не хранится — его считают по `points_history`.

| Колонка | Тип | Описание |
|---------|-----|----------|
| `id` | UUID | Ключ |
| `email` | VARCHAR(255) | Логин, уникальный, в нижнем регистре |
| `name` | VARCHAR(100) | Имя для отображения |
| `password_hash` | VARCHAR(255) | BCrypt-хеш пароля |
| `hidden_rewards_password_hash` | VARCHAR(255) | BCrypt-хеш общего пароля скрытых наград; NULL — не задан. Меняется без смены `version` |
| `created_at`, `updated_at` | TIMESTAMP | Создан, изменён |
| `version` | BIGINT | Версия |

### refresh_tokens

Refresh-токены сессий входа. Хранится только хеш. Токены одной сессии объединены `family_id`.

| Колонка | Тип | Описание |
|---------|-----|----------|
| `id` | UUID | Ключ |
| `user_id` | UUID | Владелец |
| `family_id` | UUID | Сессия: общий для всех токенов, выданных при ротации |
| `token_hash` | VARCHAR(64) | SHA-256 токена, уникален |
| `created_at` | TIMESTAMP | Выдан |
| `expires_at` | TIMESTAMP | Срок этого токена |
| `session_expires_at` | TIMESTAMP | Абсолютный срок сессии |
| `revoked_at` | TIMESTAMP | Отозван (ротация, выход, повторное использование); NULL — действует |

### goals

Долгосрочная цель. При удалении её проекты и задачи остаются без цели.

| Колонка | Тип | Описание |
|---------|-----|----------|
| `id` | UUID | Ключ |
| `user_id` | UUID | Владелец |
| `title` | VARCHAR(255) | Название |
| `description` | TEXT | Описание |
| `version` | BIGINT | Версия |
| `created_at`, `updated_at` | TIMESTAMP | Создана, изменена |

### projects

Группа задач. Может быть без цели. Баллов не даёт. При удалении его задачи остаются без проекта.

| Колонка | Тип | Описание |
|---------|-----|----------|
| `id` | UUID | Ключ |
| `user_id` | UUID | Владелец |
| `goal_id` | UUID | Цель, необязательна |
| `title` | VARCHAR(255) | Название |
| `description` | TEXT | Описание |
| `calculated_difficulty` | VARCHAR(20) | Сложность, вычисленная системой: средний уровень неотменённых задач, округлённый; NULL — задач нет. Пользователь её не задаёт; пересчёт не меняет `version` |
| `status` | VARCHAR(20) | `ACTIVE`, `COMPLETED`, `CANCELLED` |
| `deadline` | DATE | Срок; не раньше сроков задач проекта (проверяет приложение) |
| `completed_at` | TIMESTAMP | Когда завершён |
| `version` | BIGINT | Версия |
| `created_at`, `updated_at` | TIMESTAMP | Создан, изменён |

### tasks

Конкретное действие; за выполнение начисляются баллы. Может быть без проекта и без цели.

| Колонка | Тип | Описание |
|---------|-----|----------|
| `id` | UUID | Ключ |
| `user_id` | UUID | Владелец |
| `project_id` | UUID | Проект, необязателен |
| `goal_id` | UUID | Цель, необязательна |
| `title` | VARCHAR(255) | Название |
| `description` | TEXT | Описание |
| `difficulty` | VARCHAR(20) | Сложность |
| `points` | INTEGER | Баллы за выполнение, 1..1000. По умолчанию — по сложности |
| `status` | VARCHAR(20) | `ACTIVE`, `COMPLETED`, `CANCELLED`. Просрочка — не статус: `ACTIVE` и `deadline` в прошлом |
| `start_date` | DATE | Дата начала; может быть в прошлом; не позже срока |
| `deadline` | DATE | Срок; может быть в прошлом; не позже срока проекта (проверяет приложение) |
| `reminder_at` | TIMESTAMP | Время напоминания |
| `completed_at` | TIMESTAMP | Когда выполнена |
| `version` | BIGINT | Версия |
| `created_at`, `updated_at` | TIMESTAMP | Создана, изменена |

### plans

Когда выполнять работу. Задаётся ровно одно: `plan_date` или `days_of_week`. Версии нет: план меняет только пользователь, AI планы лишь читает. Повторяющийся план действует со дня `created_at`.

| Колонка | Тип | Описание |
|---------|-----|----------|
| `id` | UUID | Ключ |
| `user_id` | UUID | Владелец |
| `task_id` | UUID | Задача, необязательна; удаляется вместе с задачей |
| `title` | VARCHAR(255) | Название |
| `description` | TEXT | Описание |
| `plan_date` | DATE | Дата — для разового плана |
| `days_of_week` | SMALLINT[] | Дни недели (1 = пн … 7 = вс) — для повторяющегося плана |
| `time_from`, `time_to` | TIME | Время начала и конца, конец позже начала |
| `created_at`, `updated_at` | TIMESTAMP | Создан, изменён |

### rewards

Награда, которую пользователь получает за баллы.

| Колонка | Тип | Описание |
|---------|-----|----------|
| `id` | UUID | Ключ |
| `user_id` | UUID | Владелец |
| `title` | VARCHAR(255) | Название |
| `description` | TEXT | Описание |
| `cost` | INTEGER | Стоимость в баллах, > 0; приложение ограничивает 1..1 000 000. Награду можно получать повторно |
| `hidden` | BOOLEAN | Скрытая: видна только по паролю, недоступна AI |
| `version` | BIGINT | Версия |
| `created_at`, `updated_at` | TIMESTAMP | Создана, изменена |

### points_history

Все операции с баллами. Источник правды для баланса: баланс = сумма `amount`. Записи только добавляются.

| Колонка | Тип | Описание |
|---------|-----|----------|
| `id` | BIGSERIAL | Ключ |
| `user_id` | UUID | Владелец |
| `kind` | VARCHAR(20) | `TASK_COMPLETED` — начисление, `REWARD_REDEEMED` — списание |
| `amount` | INTEGER | Начисление > 0, списание < 0 |
| `reason` | VARCHAR(255) | Описание операции |
| `task_id` | UUID | Задача начисления. Одна задача — одно начисление. После удаления задачи — NULL |
| `reward_id` | UUID | Награда списания. После удаления награды — NULL |
| `created_at` | TIMESTAMP | Когда |

### task_completions

Факт выполнения задачи для аналитики. Сохраняется после удаления задачи.

| Колонка | Тип | Описание |
|---------|-----|----------|
| `id` | BIGSERIAL | Ключ |
| `user_id` | UUID | Владелец |
| `task_id` | UUID | Задача; после удаления — NULL |
| `difficulty` | VARCHAR(20) | Сложность на момент выполнения |
| `points` | INTEGER | Начисленные баллы |
| `planned_date` | DATE | На какой день была запланирована: самая поздняя `plan_date` разовых планов задачи; NULL — разовых планов нет |
| `deadline` | DATE | Срок на момент выполнения |
| `completed_at` | TIMESTAMP | Когда выполнена |

### task_reschedules

История переносов плановой даты задачи. Запись появляется, когда у разового плана той же задачи меняется `plan_date`. Сохраняется после удаления задачи.

| Колонка | Тип | Описание |
|---------|-----|----------|
| `id` | BIGSERIAL | Ключ |
| `user_id` | UUID | Владелец |
| `task_id` | UUID | Задача; после удаления — NULL |
| `from_date` | DATE | Было запланировано на |
| `to_date` | DATE | Перенесено на |
| `rescheduled_at` | TIMESTAMP | Когда перенесли |

### reflection_questions

Вопросы после выполнения задачи: стандартные и созданные пользователем. Три стандартных вопроса создаются миграцией.

| Колонка | Тип | Описание |
|---------|-----|----------|
| `id` | UUID | Ключ |
| `user_id` | UUID | Автор; NULL — стандартный вопрос |
| `text` | VARCHAR(500) | Текст вопроса |
| `created_at` | TIMESTAMP | Создан |

### task_reflection_questions

Какие вопросы задать после выполнения задачи. Нет строк — рефлексия для задачи отключена. Новой задаче назначаются все стандартные вопросы.

| Колонка | Тип | Описание |
|---------|-----|----------|
| `task_id` | UUID | Задача |
| `question_id` | UUID | Вопрос |

### reflection_answers

Ответы на вопросы рефлексии. Привязаны к факту выполнения, поэтому сохраняются после удаления задачи. Один ответ на вопрос для факта выполнения (проверяет приложение); ответы не меняются.

| Колонка | Тип | Описание |
|---------|-----|----------|
| `id` | BIGSERIAL | Ключ |
| `user_id` | UUID | Владелец |
| `completion_id` | BIGINT | Факт выполнения (`task_completions`) |
| `question_id` | UUID | Вопрос; после удаления — NULL |
| `question_text` | VARCHAR(500) | Текст вопроса на момент ответа |
| `answer` | TEXT | Ответ |
| `created_at` | TIMESTAMP | Когда ответил |

### user_sound_settings

Звук, выбранный пользователем для события, связанного с задачами. Нет строки — звук по умолчанию (определяет клиент).

| Колонка | Тип | Описание |
|---------|-----|----------|
| `user_id` | UUID | Пользователь |
| `event` | VARCHAR(50) | `TASK_CREATED`, `TASK_COMPLETED`, `TASK_CANCELLED`, `TASK_REMINDER`, `TASK_OVERDUE` (перечень в коде, `SoundEvent`) |
| `sound` | VARCHAR(100) | Ключ звука из каталога клиента |

### ai_consents

Согласие на использование данных для AI. Даётся один раз; без него AI недоступен.

| Колонка | Тип | Описание |
|---------|-----|----------|
| `user_id` | UUID | Пользователь, ключ |
| `accepted_at` | TIMESTAMP | Когда согласился |

### ai_requests

Один запрос пользователя к AI. Сырое сообщение не хранится.

| Колонка | Тип | Описание |
|---------|-----|----------|
| `id` | UUID | Ключ |
| `user_id` | UUID | Владелец |
| `status` | VARCHAR(30) | `PENDING_AI`, `EXECUTED`, `PROPOSED`, `ANSWERED`, `NEEDS_CLARIFICATION`, `FAILED`, `REJECTED`, `EXPIRED`. Активный (`PENDING_AI`) — не больше одного на пользователя |
| `idempotency_key` | VARCHAR(100) | Ключ повтора от клиента, уникален для пользователя |
| `request_hash` | VARCHAR(64) | Хеш тела запроса: тот же ключ с другим телом — ошибка |
| `message_length`, `message_hash` | INTEGER, VARCHAR(64) | Длина и хеш сообщения пользователя |
| `handles` | JSONB | Дескрипторы, выданные AI: дескриптор → тип, id, версия |
| `data_access_log` | JSONB | Что AI прочитал (тип запроса, количество), без содержимого |
| `plan` | JSONB | Проверенный план операций |
| `decision` | JSONB | Подтверждённые пользователем изменения и удаления |
| `result` | JSONB | Отчёт об операциях |
| `answer` | TEXT | Текстовый ответ AI |
| `contract_version`, `prompt_version` | VARCHAR | Версии контракта и промпта |
| `provider`, `model` | VARCHAR | Провайдер и модель AI |
| `error_code` | VARCHAR(50) | Код ошибки, если запрос не удался |
| `ai_calls`, `data_rounds` | INTEGER | Вызовов AI, раундов запроса данных |
| `input_tokens`, `output_tokens` | INTEGER | Токены |
| `latency_ms` | INTEGER | Длительность |
| `version` | BIGINT | Версия |
| `created_at`, `expires_at`, `executed_at` | TIMESTAMP | Создан, истекает, исполнен |

### ai_memories

Выводы, которые пользователь сохранил в памяти AI для будущих запросов.

| Колонка | Тип | Описание |
|---------|-----|----------|
| `id` | UUID | Ключ |
| `user_id` | UUID | Владелец |
| `content` | TEXT | Текст вывода |
| `created_at` | TIMESTAMP | Сохранён |

### audit_log

Журнал действий пользователя и AI над сущностями.

| Колонка | Тип | Описание |
|---------|-----|----------|
| `id` | BIGSERIAL | Ключ |
| `user_id` | UUID | Кто; после удаления пользователя — NULL |
| `action` | VARCHAR(50) | Действие, например `TASK_CREATED`, `TASK_DELETED` |
| `entity_type` | VARCHAR(50) | `GOAL`, `PROJECT`, `TASK`, `REWARD`, `PLAN` |
| `entity_id` | UUID | Сущность |
| `details` | JSONB | Подробности, например `{"source": "AI", "requestId": …, "fields": [...]}` — имена полей без значений |
| `created_at` | TIMESTAMP | Когда |

### error_log

Журнал ошибок приложения для диагностики.

| Колонка | Тип | Описание |
|---------|-----|----------|
| `id` | BIGSERIAL | Ключ |
| `timestamp` | TIMESTAMP | Когда |
| `level` | VARCHAR(10) | `ERROR`, `WARN` |
| `logger_name` | VARCHAR(255) | Логгер |
| `message` | TEXT | Сообщение |
| `exception_class`, `exception_message`, `stack_trace` | VARCHAR / TEXT | Исключение |
| `request_id` | VARCHAR(50) | ID запроса для связи записей |
| `user_id` | UUID | Пользователь; после удаления — NULL |
| `endpoint`, `http_method` | VARCHAR | HTTP-запрос |
