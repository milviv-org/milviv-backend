# 📚 Technology Decisions

Подробная документация о выборе технологий в проекте MILVIV.

> **Быстрая справка:** Краткие комментарии по каждой библиотеке см. в [`build.gradle`](../build.gradle)

---

## 📖 Содержание

1. [Философия проекта](#философия-проекта)
2. [Core технологии](#core-технологии)
3. [База данных](#база-данных)
4. [HTTP сервер](#http-сервер)
5. [JSON обработка](#json-обработка)
6. [Логирование](#логирование)
7. [Безопасность](#безопасность)
8. [Конфигурация](#конфигурация)
9. [Тестирование](#тестирование)

---

## 🎯 Философия проекта

### Почему БЕЗ Spring Framework?

**Наш выбор:** Чистая Java 25 без фреймворков

#### ✅ Преимущества:

1. **Легковесность**
   - ~50 MB памяти vs ~200+ MB для Spring Boot
   - Старт 1-2 секунды vs 5-10 секунд

2. **Полный контроль**
   - Явная конфигурация без "магии"
   - Понимание каждой строки кода
   - Контроль над lifecycle компонентов

3. **Обучение**
   - Глубокое понимание HTTP протокола
   - Ручная реализация DI контейнера
   - Понимание как работают фреймворки "под капотом"

4. **Простота debugging**
   - Нет сложных stack traces от Spring
   - Прямые вызовы методов

#### ⚠️ Когда Spring лучше:

- **Enterprise проекты** с 10+ микросервисами
- **Готовая экосистема**: Spring Security, Spring Data, Spring Cloud
- **Большая команда** - стандартизация подходов
- **Быстрая разработка** - много готовых решений из коробки

---

## 🔧 Core технологии

### Java 25

**Что нового:**
- Virtual Threads (Project Loom) - легковесные потоки
- Pattern Matching для switch
- Record Patterns
- Улучшения в Stream API

**Почему не LTS (Java 21):**
- Хотим использовать новейшие фичи
- Учебный проект - можем позволить себе bleeding edge
- Production проекты обычно используют LTS

---

## 💾 База данных

### PostgreSQL + HikariCP + Flyway

#### PostgreSQL (через JDBC Driver)

**Выбрали PostgreSQL вместо:**

| БД | Почему НЕ выбрали | Когда лучше |
|----|-------------------|-------------|
| **MySQL** | Меньше фич, слабее транзакции | LAMP стек, простые проекты |
| **MongoDB** | NoSQL - избыточно для CRUD | Гибкая схема, большие объемы |
| **SQLite** | Для embedded, нет сетевого режима | Mobile, desktop приложения |
| **Oracle** | Дорого, enterprise | Корпоративный сегмент |

**PostgreSQL лучше потому что:**
- ✅ ACID транзакции
- ✅ JSON поддержка (JSONB)
- ✅ Rich типы данных (массивы, ENUM, UUID)
- ✅ Мощные индексы (GiST, GIN, BRIN)
- ✅ Open source, активное сообщество

---

#### HikariCP (Connection Pool)

**Зачем нужен Connection Pool?**

Без пула:
```java
// ПЛОХО: Каждый запрос = новое подключение к БД
Connection conn = DriverManager.getConnection(url); // 50-100ms !
// Используем...
conn.close();
```

С HikariCP:
```java
// ХОРОШО: Переиспользуем готовые подключения
Connection conn = dataSource.getConnection(); // <1ms !
// Используем...
conn.close(); // Возвращает в пул, не закрывает физически
```

**Бенчмарки (запросов в секунду):**

| Connection Pool | Производительность | Примечание |
|-----------------|-------------------|------------|
| **HikariCP** | 200,000+ req/s | 🏆 Fastest |
| Tomcat Pool | ~150,000 req/s | Хорошо |
| Apache DBCP2 | ~100,000 req/s | Устарел |
| C3P0 | ~50,000 req/s | Legacy код |

**Вердикт:** HikariCP - это **индустриальный стандарт**. Spring Boot использует его по умолчанию с версии 2.0.

---

#### Flyway (Database Migrations)

**Зачем миграции?**

Проблема без Flyway:
- ❌ Разработчики вручную применяют SQL скрипты
- ❌ Не знаем какая версия схемы на production
- ❌ Ошибки при деплое (забыли применить миграцию)

Решение с Flyway:
- ✅ Версионные SQL файлы: `V1__create_users.sql`, `V2__add_email_column.sql`
- ✅ Автоматическое применение при старте приложения
- ✅ История в таблице `flyway_schema_history`

**Flyway vs Liquibase:**

| Аспект | Flyway | Liquibase |
|--------|--------|-----------|
| **Формат** | SQL файлы | XML/YAML/JSON/SQL |
| **Сложность** | ✅ Простой | ⚠️ Сложнее |
| **DB-независимость** | ❌ Нет | ✅ Да |
| **Rollback** | Ручной (Down миграции) | ✅ Автоматический |
| **Условная логика** | ❌ Нет | ✅ if/else в XML |

**Наш выбор: Flyway**
- У нас одна БД (PostgreSQL) - DB-независимость не нужна
- Чистый SQL читабельнее XML
- Простота важнее гибкости

---

## 🌐 HTTP сервер

### Jetty

**Сравнение HTTP серверов:**

| Сервер | Память | Производительность | Сложность API | Экосистема |
|--------|--------|-------------------|---------------|------------|
| **Jetty** | 30-50 MB | ⚡⚡⚡⚡ | ✅ Средняя | Большая |
| **Netty** | 40 MB | ⚡⚡⚡⚡⚡ Fastest | ❌ Сложная | Средняя |
| **Tomcat** | 60-80 MB | ⚡⚡⚡ | ⚠️ Больше кода | Огромная |
| **Undertow** | 35 MB | ⚡⚡⚡⚡ | ✅ Средняя | Малая |

**Почему Jetty:**
- ✅ Легко встраивается в приложение
- ✅ Хорошая документация и примеры
- ✅ Стабильность (используется в NASA, Eclipse)
- ✅ Servlet API поддержка
- ✅ Баланс производительности и простоты

**Когда другие лучше:**
- **Netty**: Максимальная производительность (WebSocket, HTTP/2, reactive)
- **Tomcat**: Нужна огромная экосистема и enterprise поддержка
- **Undertow**: Чуть легче Jetty, используется в WildFly

---

## 📦 JSON обработка

### Jackson

**Сравнение JSON библиотек:**

| Библиотека | Скорость | Размер | Фичи | Сложность |
|------------|----------|--------|------|-----------|
| **Jackson** | ⚡⚡⚡⚡ | 1.5 MB | ⭐⭐⭐⭐⭐ | Средняя |
| **Gson** | ⚡⚡⚡ | 240 KB | ⭐⭐⭐ | ✅ Простая |
| **JSON-B** | ⚡⚡⚡ | 600 KB | ⭐⭐⭐⭐ | Средняя |
| **Moshi** | ⚡⚡⚡⚡ | 120 KB | ⭐⭐⭐ | ✅ Простая |

**Jackson бенчмарки:** (сериализация 1000 объектов)
- Jackson: **~15ms**
- Gson: ~30ms (в 2х медленнее)
- JSON-B: ~25ms

**Почему Jackson:**
- ✅ Fastest в категории (critical для REST API)
- ✅ `jackson-datatype-jsr310` - поддержка `LocalDateTime` из коробки
- ✅ Кастомные сериализаторы/десериализаторы
- ✅ Аннотации для контроля: `@JsonProperty`, `@JsonIgnore`
- ✅ Поддержка XML, YAML, CSV через модули

**Когда Gson лучше:**
- Нужна максимальная простота
- Размер библиотеки критичен
- Базовая сериализация без сложностей

---

## 📝 Логирование

### SLF4J + Logback

**Почему SLF4J (фасад)?**

```java
// Используем интерфейс SLF4J
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

Logger logger = LoggerFactory.getLogger(MyClass.class);
logger.info("User {} logged in", userId);
```

**Преимущество:** Можем поменять реализацию (Logback → Log4j2) без изменения кода!

**Сравнение реализаций:**

| Библиотека | Производительность | Фичи | Конфигурация |
|------------|-------------------|------|--------------|
| **Logback** | ⚡⚡⚡⚡ | ⭐⭐⭐⭐⭐ | XML/Groovy |
| **Log4j2** | ⚡⚡⚡⚡⚡ Fastest | ⭐⭐⭐⭐⭐ | XML/JSON/YAML |
| **java.util.logging** | ⚡⚡ | ⭐⭐ | Properties |

**Почему Logback:**
- ✅ Автоматическая перезагрузка конфигурации без перезапуска
- ✅ MDC (Mapped Diagnostic Context) - request ID для всех логов запроса
- ✅ Условные фильтры и разные appenders
- ✅ Стандарт в Spring Boot

**Когда Log4j2 лучше:**
- Нужна максимальная производительность (в 2-10х быстрее)
- Async loggers с zero garbage collection
- High-load проекты (100k+ RPS)

---

## 🔐 Безопасность

### BCrypt

**Почему нельзя MD5/SHA для паролей:**

| Алгоритм | Взлом (паролей/сек) | Salt | Защита от GPU |
|----------|---------------------|------|---------------|
| **MD5** | 1,000,000,000 | ❌ | ❌ |
| **SHA-256** | 100,000,000 | ❌ | ❌ |
| **BCrypt** | 100-1,000 | ✅ Auto | ✅ |
| **Argon2** | 10-100 | ✅ Auto | ✅ Best |
| **PBKDF2** | 1,000-10,000 | ✅ Auto | ✅ |

**Как работает BCrypt:**

```java
// Cost factor = 12 → 2^12 = 4096 раундов хеширования (~300ms)
String hashed = BCrypt.hashpw("password123", BCrypt.gensalt(12));
// $2a$12$R9h/cIPz0gi.URNNX3kh2OPST9/PgBkqquzi.Ss7KIUgO2t0jWMUW

// Проверка
boolean valid = BCrypt.checkpw("password123", hashed); // true
```

**Cost factor рекомендации:**
- `10` - быстро (~100ms), минимум для production
- `12` - **рекомендуется** (~300ms), баланс
- `15` - очень медленно (~2 сек), максимальная защита

**Почему BCrypt, а не Argon2:**
- ✅ BCrypt - проверенный стандарт (1999 год)
- ✅ Простая библиотека
- ✅ Достаточная защита для большинства проектов
- ⚠️ Argon2 лучше защищён от GPU/ASIC атак (winner PHC 2015)

**Когда Argon2 лучше:**
- Максимальная безопасность (банки, криптовалюты)
- Защита от специализированного hardware

---

## ⚙️ Конфигурация

### dotenv-java

**12-Factor App принцип:**
> III. Config: Store config in the environment

**Проблема:**
```java
// ❌ ПЛОХО: Хардкод в коде
String dbUrl = "jdbc:postgresql://localhost:5432/mydb";
String dbPassword = "super_secret_123"; // В Git попадёт!
```

**Решение:**
```bash
# .env файл (добавлен в .gitignore)
DB_URL=jdbc:postgresql://localhost:5432/mydb
DB_PASSWORD=super_secret_123
SERVER_PORT=8080
```

```java
// ✅ ХОРОШО: Читаем из окружения
Dotenv dotenv = Dotenv.load();
String dbUrl = dotenv.get("DB_URL");
```

**Преимущества:**
- ✅ Secrets не попадают в Git
- ✅ Разные конфиги для dev/staging/prod
- ✅ Легко менять без пересборки

**Альтернативы:**
- `System.getenv()` - менее удобно (нужны переменные окружения)
- Spring Config Server - для distributed configuration
- Vault - для enterprise secrets management

---

## 🧪 Тестирование

### Стратегия тестирования

**3 уровня тестов:**

1. **Unit тесты** (быстрые, изолированные)
   - JUnit 5 + Mockito + AssertJ
   - H2 in-memory база
   - ~100ms на прогон

2. **Integration тесты** (медленные, реальная БД)
   - Testcontainers + PostgreSQL
   - Реальная БД в Docker
   - ~5-10 сек на прогон

3. **E2E тесты** (полный стек)
   - REST Assured
   - Живой HTTP сервер
   - ~10-30 сек на прогон

---

### Unit тесты

#### JUnit 5
- ✅ Индустриальный стандарт
- ✅ `@ParameterizedTest` для data-driven тестов
- ✅ Extension модель (BeforeEach, AfterEach)

#### Mockito
```java
@Mock
UserRepository userRepository;

@Test
void shouldFindUser() {
    when(userRepository.findById(1))
        .thenReturn(Optional.of(user));
    
    // Тестируем Service, не реальную БД
}
```

#### AssertJ
```java
// ❌ JUnit style
assertEquals("John", user.getName());

// ✅ AssertJ style - читаемо!
assertThat(user)
    .hasFieldOrPropertyWithValue("name", "John")
    .extracting(User::getAge)
    .isGreaterThan(18);
```

#### H2 Database
- ✅ Быстрые тесты (~100ms)
- ✅ Не требует Docker
- ⚠️ Не 100% совместим с PostgreSQL
- ⚠️ Может пропустить PostgreSQL-специфичные баги

---

### Integration тесты

#### Testcontainers

**Зачем:** Найти баги специфичные для PostgreSQL

```java
@Testcontainers
class DatabaseIntegrationTest {
    @Container
    static PostgreSQLContainer<?> postgres = 
        new PostgreSQLContainer<>("postgres:15");
    
    @Test
    void shouldUseRealPostgreSQL() {
        // Полная уверенность в SQL запросах
    }
}
```

**Что дает:**
- ✅ Реальная PostgreSQL в Docker
- ✅ Автоматический старт/стоп контейнера
- ✅ Изоляция - свежая БД для каждого теста
- ✅ CI/CD friendly

---

### E2E тесты

#### REST Assured

```java
given()
    .contentType("application/json")
    .body("""
        {"name": "John", "level": "EXPERT"}
    """)
.when()
    .post("/api/tasks")
.then()
    .statusCode(201)
    .body("name", equalTo("John"))
    .body("level", equalTo("EXPERT"))
    .body("points", equalTo(50));
```

**Преимущества:**
- ✅ Тестируем как реальный клиент
- ✅ Валидация JSON responses
- ✅ Поддержка всех HTTP методов

---

## 📊 Итоговая таблица: Наш стек

| Категория | Библиотека | Почему именно она |
|-----------|------------|-------------------|
| **Language** | Java 25 | Virtual Threads, новейшие фичи |
| **Database** | PostgreSQL | ACID, JSON, rich типы |
| **Connection Pool** | HikariCP | Fastest (200k+ req/s) |
| **Migrations** | Flyway | Простота, SQL-based |
| **HTTP Server** | Jetty | Баланс производительности/простоты |
| **JSON** | Jackson | Fastest, rich features |
| **Logging** | SLF4J + Logback | Гибкость, стандарт |
| **Password** | BCrypt | Проверенный стандарт |
| **Config** | dotenv-java | 12-factor app принцип |
| **Testing** | JUnit 5 + Mockito + REST Assured | Полное покрытие |

---

## 🎓 Дополнительные ресурсы

### Официальная документация

- [PostgreSQL Documentation](https://www.postgresql.org/docs/)
- [HikariCP GitHub](https://github.com/brettwooldridge/HikariCP)
- [Flyway Documentation](https://flywaydb.org/documentation/)
- [Jackson Documentation](https://github.com/FasterXml/jackson-docs)
- [Jetty Documentation](https://eclipse.dev/jetty/documentation/)
- [SLF4J Manual](https://www.slf4j.org/manual.html)
- [Logback Manual](https://logback.qos.ch/manual/)
- [BCrypt — Wikipedia](https://en.wikipedia.org/wiki/Bcrypt)
- [JUnit 5 User Guide](https://junit.org/junit5/docs/current/user-guide/)
- [Mockito Documentation](https://javadoc.io/doc/org.mockito/mockito-core/latest/org/mockito/Mockito.html)
- [AssertJ Documentation](https://assertj.github.io/doc/)
- [Testcontainers Documentation](https://www.testcontainers.org/)
- [REST Assured Documentation](https://rest-assured.io/)

### Статьи и бенчмарки

- [HikariCP Benchmark Results](https://github.com/brettwooldridge/HikariCP-benchmark)
- [Jackson vs Gson Performance](https://www.baeldung.com/jackson-vs-gson)
- [Password Hashing Competition](https://password-hashing.net/)
- [The Twelve-Factor App](https://12factor.net/)

---

**Последнее обновление:** 15 февраля 2026  
**Автор:** GitHub Copilot + Your decisions
