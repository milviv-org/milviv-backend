package com.milviv.unit.infrastructure.container;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.milviv.infrastructure.container.DIContainer;
import com.milviv.infrastructure.container.DIContainerException;
import com.milviv.infrastructure.container.Inject;
import com.milviv.infrastructure.container.Scope;

class DIContainerTest {

    // ─── Test fixtures ────────────────────────────────────────────────────────
    // Простые классы без зависимостей от проекта — тест изолирован

    static class Engine {}

    static class Car {
        final Engine engine;

        Car(Engine engine) {
            this.engine = engine;
        }
    }

    interface Repository {}

    static class PostgresRepository implements Repository {
        PostgresRepository() {}
    }

    static class Service {
        final Repository repo;

        Service(Repository repo) {
            this.repo = repo;
        }
    }

    // Два конструктора — @Inject указывает нужный
    static class MultiCtorService {
        final Engine engine;

        MultiCtorService() {
            this.engine = null;
        }

        @Inject
        MultiCtorService(Engine engine) {
            this.engine = engine;
        }
    }

    // Два конструктора без @Inject — контейнер не знает какой выбрать
    static class AmbiguousService {
        AmbiguousService() {}
        AmbiguousService(Engine engine) {}
    }

    // Циклические зависимости: Alpha → Beta → Alpha
    static class Alpha {
        Alpha(Beta beta) {}
    }

    static class Beta {
        Beta(Alpha alpha) {}
    }

    // ─── Setup ────────────────────────────────────────────────────────────────

    private DIContainer container;

    @BeforeEach
    void setUp() {
        container = new DIContainer();
    }

    // ─── Tests ────────────────────────────────────────────────────────────────

    @Test
    void shouldCreateSimpleClassWithNoDependencies() throws DIContainerException {
        // given
        container.register(Engine.class);

        // when
        Engine engine = container.get(Engine.class);

        // then
        assertThat(engine).isNotNull();
        assertThat(engine).isInstanceOf(Engine.class);
    }

    @Test
    void shouldInjectDependencyThroughConstructor() throws DIContainerException {
        // given
        container.register(Engine.class);
        container.register(Car.class);

        // when
        Car car = container.get(Car.class);

        // then
        assertThat(car).isNotNull();
        assertThat(car.engine).isNotNull();
    }

    @Test
    void shouldReturnSameInstanceForSingleton() throws DIContainerException {
        // given
        container.register(Engine.class, Scope.SINGLETON);

        // when
        Engine first = container.get(Engine.class);
        Engine second = container.get(Engine.class);

        // then — тот же объект в памяти
        assertThat(first).isSameAs(second);
    }

    @Test
    void shouldReturnNewInstanceForPrototype() throws DIContainerException {
        // given
        container.register(Engine.class, Scope.PROTOTYPE);

        // when
        Engine first = container.get(Engine.class);
        Engine second = container.get(Engine.class);

        // then — разные объекты
        assertThat(first).isNotSameAs(second);
    }

    @Test
    void shouldResolveInterfaceToImplementation() throws DIContainerException {
        // given
        container.register(Repository.class, PostgresRepository.class);

        // when
        Repository repo = container.get(Repository.class);

        // then
        assertThat(repo).isNotNull();
        assertThat(repo).isInstanceOf(PostgresRepository.class);
    }

    @Test
    void shouldInjectInterfaceDependency() throws DIContainerException {
        // given
        container.register(Repository.class, PostgresRepository.class);
        container.register(Service.class);

        // when
        Service service = container.get(Service.class);

        // then
        assertThat(service.repo).isNotNull();
        assertThat(service.repo).isInstanceOf(PostgresRepository.class);
    }

    @Test
    void shouldReturnPreBuiltSingleton() throws DIContainerException {
        // given — объект создан вне контейнера
        Engine preBuilt = new Engine();
        container.singletone(Engine.class, preBuilt);

        // when
        Engine result = container.get(Engine.class);

        // then — контейнер вернул именно тот объект
        assertThat(result).isSameAs(preBuilt);
    }

    @Test
    void shouldUseAnnotatedConstructorWhenMultipleExist() throws DIContainerException {
        // given
        container.register(Engine.class);
        container.register(MultiCtorService.class);

        // when
        MultiCtorService service = container.get(MultiCtorService.class);

        // then — использован конструктор с @Inject (Engine не null)
        assertThat(service.engine).isNotNull();
    }

    @Test
    void shouldShareSingletonAcrossMultipleDependents() throws DIContainerException {
        // given — Engine нужен и Car, и напрямую
        container.register(Engine.class, Scope.SINGLETON);
        container.register(Car.class);

        // when
        Car car = container.get(Car.class);
        Engine standalone = container.get(Engine.class);

        // then — Car получил тот же singleton что и прямой вызов get()
        assertThat(car.engine).isSameAs(standalone);
    }

    // ─── Error cases ──────────────────────────────────────────────────────────

    @Test
    void shouldThrowWhenTypeNotRegistered() {
        // given — Engine не зарегистрирован

        // when / then
        assertThatThrownBy(() -> container.get(Engine.class))
                .isInstanceOf(DIContainerException.class)
                .hasMessageContaining("Engine");
    }

    @Test
    void shouldThrowWhenRegisteringSameTypeTwice() throws DIContainerException {
        // given
        container.register(Engine.class);

        // when / then
        assertThatThrownBy(() -> container.register(Engine.class))
                .isInstanceOf(DIContainerException.class)
                .hasMessageContaining("already registered");
    }

    @Test
    void shouldThrowWhenRegisteringSameSingletonTwice() throws DIContainerException {
        // given
        container.singletone(Engine.class, new Engine());

        // when / then
        assertThatThrownBy(() -> container.singletone(Engine.class, new Engine()))
                .isInstanceOf(DIContainerException.class)
                .hasMessageContaining("already registered");
    }

    @Test
    void shouldThrowOnCircularDependency() throws DIContainerException {
        // given — Alpha → Beta → Alpha
        container.register(Alpha.class);
        container.register(Beta.class);

        // when / then
        assertThatThrownBy(() -> container.get(Alpha.class))
                .isInstanceOf(DIContainerException.class)
                .hasMessageContaining("Circular dependency");
    }

    @Test
    void shouldThrowWhenMultipleConstructorsWithoutInject() throws DIContainerException {
        // given — register() не проверяет конструкторы, ошибка возникает при get()
        container.register(Engine.class);
        container.register(AmbiguousService.class);

        // when / then
        assertThatThrownBy(() -> container.get(AmbiguousService.class))
                .isInstanceOf(DIContainerException.class)
                .hasMessageContaining("@Inject");
    }

    @Test
    void shouldThrowWhenDependencyNotRegistered() throws DIContainerException {
        // given — Car нужен Engine, но Engine не зарегистрирован
        container.register(Car.class);

        // when / then
        assertThatThrownBy(() -> container.get(Car.class))
                .isInstanceOf(DIContainerException.class)
                .hasMessageContaining("Engine");
    }
}
