# MILVIV

Геймификация задач с RPG-системой уровней сложности.

## 📋 Описание

MILVIV - это приложение для управления задачами и проектами с элементами геймификации. Выполняйте задачи, получайте баллы и накапливайте их для материальных вознаграждений.

### Основные возможности

- **Задачи** с 6 уровнями сложности (Новичок → Легендарный)
- **Проекты** для группировки связанных задач
- **Статистика** выполненных, просроченных и невыполненных задач
- **Планирование** с временными интервалами на день/неделю
- **История действий** и аудит
- **Система баллов** за выполнение задач

### Уровни сложности

| Уровень | Английский | Баллы |
|---------|-----------|-------|
| Новичок | NOVICE | 5 |
| Ученик | APPRENTICE | 10 |
| Адепт | ADEPT | 20 |
| Эксперт | EXPERT | 50 |
| Мастер | MASTER | 100 |
| Легендарный | LEGENDARY | 200 |

## 🏗️ Архитектура

Проект построен на принципах **Clean Architecture** и **Domain-Driven Design (DDD)**.

```
src/
├── domain/              # Бизнес-логика (независимая от внешних зависимостей)
├── application/         # Use Cases (сценарии использования)
├── infrastructure/      # Внешние зависимости (БД, логирование)
└── presentation/        # REST API endpoints
```

## 🛠️ Технологический стек

> 📖 **Подробная документация:** [docs/TECHNOLOGY_DECISIONS.md](docs/TECHNOLOGY_DECISIONS.md) — почему именно эти технологии и сравнение с альтернативами

**Backend:**
- Java 25 (БЕЗ фреймворков — чистая Java, полный контроль)
- PostgreSQL + HikariCP (fastest connection pool)
- Jetty (легковесный HTTP сервер)
- Jackson (fastest JSON библиотека)
- Flyway (миграции БД)
- SLF4J + Logback (логирование)
- BCrypt (безопасное хеширование паролей)

**Сборка:**
- Gradle 8+

**Тестирование:**
- JUnit 5 + Mockito + AssertJ (unit тесты)
- H2 Database (in-memory для быстрых тестов)
- Testcontainers + PostgreSQL (integration тесты)
- REST Assured (E2E тесты API)

**Философия:**
✅ Легковесность (~50 MB vs ~200+ MB для Spring Boot)  
✅ Полный контроль над архитектурой и зависимостями  
✅ Обучение — понимание как работают веб-фреймворки "под капотом"

## 🚀 Установка и запуск

### Требования

- Java 25+
- PostgreSQL 15+
- Gradle 8+ (или используйте Gradle Wrapper)

### Настройка базы данных

1. Создайте базу данных PostgreSQL:
```sql
CREATE DATABASE milviv;
```

2. Настройте подключение в `src/main/resources/application.properties` или через environment variables:
```bash
export DB_URL=jdbc:postgresql://localhost:5432/milviv
export DB_USERNAME=your_username
export DB_PASSWORD=your_password
```

### Запуск миграций

SQL миграции находятся в `src/main/resources/db/migration/`. Они будут выполнены автоматически при первом запуске приложения.

### Сборка проекта

```bash
# Сборка проекта
./gradlew build

# Создание fat JAR (со всеми зависимостями)
./gradlew fatJar
```

### Запуск приложения

```bash
# Через Gradle
./gradlew run

# Или через JAR
java -jar build/libs/milviv-all-1.0.0.jar
```

По умолчанию сервер запустится на `http://localhost:8080`

## 📡 REST API

### Задачи

```
GET    /api/tasks              - Получить все задачи
GET    /api/tasks/{id}         - Получить задачу по ID
POST   /api/tasks              - Создать задачу
PUT    /api/tasks/{id}         - Обновить задачу
DELETE /api/tasks/{id}         - Удалить задачу
POST   /api/tasks/{id}/complete - Завершить задачу
```

### Проекты

```
GET    /api/projects           - Получить все проекты
GET    /api/projects/{id}      - Получить проект по ID
POST   /api/projects           - Создать проект
PUT    /api/projects/{id}      - Обновить проект
DELETE /api/projects/{id}      - Удалить проект
POST   /api/projects/{id}/tasks/{taskId} - Добавить задачу в проект
```

### Статистика

```
GET    /api/statistics/tasks   - Статистика по задачам
GET    /api/statistics/projects - Статистика по проектам
GET    /api/statistics/points  - История баллов
```

### Планирование

```
GET    /api/plans?date=2026-01-04  - План на конкретную дату
POST   /api/plans                  - Создать план
PUT    /api/plans/{id}             - Обновить план
```

### Логи

```
GET    /api/audit?page=1&size=50   - История действий
GET    /api/errors?page=1&level=ERROR - Логи ошибок
```

## 🧪 Тестирование

```bash
# Запуск всех тестов
./gradlew test

# Запуск unit тестов
./gradlew test --tests "com.milviv.unit.*"

# Запуск integration тестов
./gradlew test --tests "com.milviv.integration.*"

# Запуск E2E тестов
./gradlew test --tests "com.milviv.e2e.*"
```

**Testcontainers** автоматически запустит PostgreSQL в Docker для интеграционных и E2E тестов.

## 📝 Конфигурация

### application.properties

```properties
# Database
db.url=jdbc:postgresql://localhost:5432/milviv
db.username=postgres
db.password=postgres

# Server
server.port=8080
server.host=0.0.0.0
```

### Environment Variables

Переменные окружения переопределяют настройки из properties:

```bash
DB_URL=jdbc:postgresql://prod-server:5432/milviv
DB_USERNAME=prod_user
DB_PASSWORD=prod_password
SERVER_PORT=8080
```

## 📂 Структура проекта

```
milviv/
├── src/
│   ├── main/
│   │   ├── java/com/milviv/
│   │   │   ├── domain/              # Entities, Value Objects, Repository interfaces
│   │   │   ├── application/         # Use Cases
│   │   │   ├── infrastructure/      # Repository implementations, DB config
│   │   │   └── presentation/        # REST controllers, DTOs
│   │   └── resources/
│   │       ├── application.properties
│   │       ├── logback.xml
│   │       └── db/migration/        # SQL миграции
│   └── test/
│       ├── java/com/milviv/
│       │   ├── unit/                # Unit тесты
│       │   ├── integration/         # Integration тесты
│       │   └── e2e/                 # E2E тесты
│       └── resources/
└── logs/                            # Логи приложения
```

## 🔐 Безопасность

- Пароли хешируются с использованием BCrypt
- SQL injection защита через PreparedStatement
- CORS настроен для frontend приложения

## 📊 Логирование

**Логи в файлы:**
- `logs/application.log` - все логи
- `logs/error.log` - только ошибки

**Логи в БД:**
- `audit_log` - история действий пользователя
- `error_log` - критические ошибки для мониторинга

## 🤝 Разработка

### Добавление новой миграции

Создайте файл в `src/main/resources/db/migration/`:
```
V8__your_description.sql
```

### Создание новой фичи

1. Domain entities в `domain/`
2. Use case в `application/`
3. Repository implementation в `infrastructure/persistence/jdbc/`
4. REST controller в `presentation/rest/controllers/`
5. Unit и integration тесты