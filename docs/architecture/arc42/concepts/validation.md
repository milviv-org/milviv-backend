# Валидация входных данных

Требование
1. Уметь валидировать входящие данные по след правилам: Required, MaxLength, MinLength, Max, Min, Email, Pattern, Enum
2. Должна быть 2 вида реализации валидирования данных: Аннотации и комплексно-процедурная валидация. Где в основном для легких валидации будет использоватся аннотации, для сложных динамичских комплексно-процедурная.
3. Все базовые валидации по аннотации должны иметь стандартный текст ошибки и возможнось передачи кастомного текста ошибки 
4. между валидацией по аннотации и комплексно-процедурной валидации должны быть общий классы, которые реализуют для своего правила валидацию значения и будет переиспользован для аннотации и комплексно-процедурной валидации

## Пример валидации с помощью аннотоции
```java

public enum DifficultLevel {
    LEGENDARY,
    MASTER,
    ECSPERT,
}

public class TaskDto {

  @Required
  @MaxLength(value = 50)
  @MinLength(value = 3)
  private String name;

  @Required("Описание обязательное поле")
  @MaxLength(value = 50, message = "Максимальная длина описания должна быть не более 50 символов")
  @MinLength(value = 3, message = "Минимальная длина описания должна быть не менее 3 символов")
  private String description;

  @Enum(enumClass = DifficultLevel.class)
  private DifficultLevel level;

  @Max(value = 10, message = "Количество должно быть не больше 10")
  @Min(value = 2, message = "Количество должно быть не меньше 2")
  private Integer count;

  @Valid
  private Info info;

  public String getName() { return name; }
  public String getDescription() { return description; }
  public DifficultLevel getLevel() { return level; }
  public Integer getCount() { return count; }
  public Info getInfo() {
    return info;
  }

}

```

## Пример валидации с помощью комплексно-процедурного метода

```java

class TaskCreateDto {
  private String name;
  private String description;
  private DifficultLevel level;
  private Integer count;
  private String email;
  private String slug;

  public String getName() { return name; }
  public String getDescription() { return description; }
  public DifficultLevel getLevel() { return level; }
  public Integer getCount() { return count; }
  public String getEmail() { return email; }
  public String getSlug() { return slug; }
}

@Controller("/tasks")
class TaskController {

  private final ComplextValidator validator;

  public TaskController(ComplextValidator validator) {
    this.validator = validator;
  }

  @Post("/")
  public void createTask(HttpContext ctx) {
    TaskCreateDto taskCreateDto = ctx.body(TaskCreateDto)

    ComplexObjectValidator<TaskCreateDto> objectValidator = validator.validateByObject(taskCreateDto)

    // пример простой валидации
    // то есть можно валидировать без аннотации
    objectValidator.required(TaskCreateDto::getName, "name")
    objectValidator.maxLength(TaskCreateDto::getName, "name", 50)
    objectValidator.minLength(TaskCreateDto::getName, "name" 3)
    objectValidator.enumValue(TaskCreateDto::getLevel, "level" DifficultLevel.class)

    objectValidator.required(TaskCreateDto::getDescription, "description", "Описание обязательное поле")
    objectValidator.maxLength(TaskCreateDto::getDescription, "description", 50, "Максимальная длина описания должно быть не более 50 символов")
    objectValidator.minLength(TaskCreateDto::getDescription, "description", 3, "Минимальная длина описания должна быть не менее 3 символов")

    objectValidator.max(TaskCreateDto::getCount, "count", 10, "Количество не должно быть больше 50")
    objectValidator.min(TaskCreateDto::getCount, "count", 3, "Количество не должно быть меньше 3")

    objectValidator.email(TaskCreateDto::getEmail, "email")
    objectValidator.pattern(TaskCreateDto::getSlug, "slug", "^[a-z0-9-]+$")

    // пример динамической валидации
    if (taskCreateDto.getLevel() == DifficultLevel.LEGENDARY) {
      objectValidator.max(TaskCreateDto::getCount, "count", 20)
    }

    // можно собрать в кучу ошибки и только тогда вызваю exception
    objectValidator.throwIfError()
  } 

}
```

## Планируемая файловая структура

```
- shared
  - validation
    - base
      - rules
        - Required
        - MaxLength
        - MinLength
        - Max
        - Min
        - Enum
        - Pattern
      - BaseValidator
      - BaseValidatorInterface
    - annotaion
      - annotaions
        - Required
        - MaxLength
        - MinLength
        - Max
        - Min
        - Enum
        - Pattern
        - Valid
      - AnnotationValidator
      - AnnotationObjectValidator
      - AnnotationValidatorInterface
      - AnnotationObjectValidatorInterface
    - complex
      - ComplexValidator
      - ComplexObjectValidator
      - ComplexValidatorInterface
      - ComplexObjectValidatorInterface
```

## Пример использования AnnotationValidator
AnnotationValidator должен использоваться внутри HttpContext метода body, что означает AnnotationValidator должен стать зависимостью HttpContext. Мне не нравится, что HttpContext знает про AnnotaionValidator, я бы сделал некоторый интерфейс под названием HttpContextBodyValidator

```java
  interface AnnotationValidatorInterface {
    AnnotationObjectValidatorInterface validateByObject(Object obj);
  }

  interface AnnotationObjectValidatorInterface {
    void validate() throws BaseValidationException;
  }

  record BaseError(String message, String field) {
  }

  class BaseValidationException extends RuntionException {

    private final List<BaseError> errors;

    public BaseValidationException(String message, List<BaseError> errors) {
      super(message);
      this.errors = errors;
    }

    public BaseValidationException(String message, String field) {
      super(message);
      this.errors = List.of(new BaseError(message, field));
    }

    public String getMessage() {
      return this.message;
    }

    public List<BaseError> getErrors() {
      return errors;
    }
    
  }

  public class AnnotationValidator implements AnnotationValidatorInterface {

    public AnnotationValidator() {}

    public validateByObject(Object obj) {
      return new AnnotationObjectValidator(obj);
    }
  }

  interface HttpContextValidator {
    void validate(Object obj) throws ValidationException;
  }

  public class AnnotationValidatorForHttpContext implements HttpContextValidator {

    private final AnnotationValidatorInterface validator;

    public AnnotationValidatorForHttpContext(AnnotationValidatorInterface validator) {
      this.validator = validator;
    }

    public validate(Object obj) throws ValidationException { 
      try {
        AnnotationObjectValidator objectValidator = validator.validateByObject(obj);
        objectValidator.validate();
      } catch(BaseValidationException bve) {
        throw new ValidationException(bve.getMessage(), bve.getErrors());
      }
    }
  }

  // в момент создание HttpContext
  new HttpContext(..., new AnnotationValidatorForHttpContext());

  // в методе HttpContext.body
  class HttpContext {
    public <T> T body(Class<T> type) throws IOException {
      String json = request.getReader().lines().collect(Collectors.joining());
      T obj = objectMapper.readValue(json, type);
      validator.validate(obj)
    }
  }
```

# Пример BaseValidator
Этот класс библеотека. будет использоваться в ComplexValidator/rules и AnnotationValidator

```java

interface BaseValidatorInterface {
  boolean required(Object value);
  boolean maxLength(String value, int length);
  boolean minLength(String value, int length);
  boolean max(Number value, Number num);
  boolean min(Number value, Number num);
  boolean email(String value);
  boolean pattern(String value, String pattern);
  boolean enumValue(Object value, Class<? extends Enum<?>> enumClass);
}

class RequiredRule {
  public boolean validate(Object value) {
    // реализация
  }
}

public class BaseValidator implements BaseValidatorInterface {
  
  private final RequiredRule requiredRule = new RequiredRule(); 

  public boolean required(Object value) {
    return requiredRule.validate(value);
  }

  // дальше другие правила
}


```