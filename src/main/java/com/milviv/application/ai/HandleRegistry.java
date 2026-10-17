package com.milviv.application.ai;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.milviv.domain.ai.AiHandle;
import com.milviv.domain.ai.OperationReport.EntityType;

/**
 * Дескрипторы одного AI-запроса (§6.4): AI видит g_k7Qx вместо UUID.
 *
 * - выдаются только сущностям, прочитанным с user_id из сессии, — подменить ID нельзя (T-7);
 * - случайны и не выводятся из ID;
 * - один объект — один дескриптор на весь запрос;
 * - тип — по префиксу, ссылка проверяется на тип места.
 *
 * Не потокобезопасен: живёт внутри одного вызова HandleAiRequest.
 */
public class HandleRegistry {

    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int LENGTH = 4;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, AiHandle> byHandle = new LinkedHashMap<>();
    private final Map<UUID, String> byId = new HashMap<>();

    public HandleRegistry() {}

    /** Восстановление сохранённых дескрипторов. */
    public HandleRegistry(List<AiHandle> handles) {
        handles.forEach(h -> {
            byHandle.put(h.handle(), h);
            byId.put(h.id(), h.handle());
        });
    }

    /** @param deadline срок проекта при чтении; у целей и наград null */
    public String issue(EntityType type, UUID id, long version, LocalDate deadline) {
        return issue(type, id, version, deadline, null);
    }

    /**
     * @param deadline  срок проекта или задачи при чтении
     * @param projectId проект задачи при чтении; у остальных типов null
     * @return дескриптор; если объект уже получал его — тот же, с обновлёнными данными
     */
    public String issue(EntityType type, UUID id, long version, LocalDate deadline, UUID projectId) {
        String existing = byId.get(id);
        if (existing != null) {
            byHandle.put(existing, new AiHandle(existing, type, id, version, deadline, projectId));
            return existing;
        }
        String handle;
        do {
            handle = prefix(type) + random();
        } while (byHandle.containsKey(handle));
        byHandle.put(handle, new AiHandle(handle, type, id, version, deadline, projectId));
        byId.put(id, handle);
        return handle;
    }

    /** Пусто — дескриптор не выдавался в этом запросе или он другого типа. */
    public Optional<AiHandle> resolve(String handle, EntityType expected) {
        if (handle == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byHandle.get(handle)).filter(h -> h.type() == expected);
    }

    /** Дескриптор любого типа — для удалений, где тип задаёт сам дескриптор. */
    public Optional<AiHandle> find(String handle) {
        return handle == null ? Optional.empty() : Optional.ofNullable(byHandle.get(handle));
    }

    public Optional<String> handleOf(UUID id) {
        return Optional.ofNullable(byId.get(id));
    }

    public List<AiHandle> snapshot() {
        return new ArrayList<>(byHandle.values());
    }

    private static String prefix(EntityType type) {
        return switch (type) {
            case GOAL -> "g_";
            case PROJECT -> "p_";
            case TASK -> "t_";
            case REWARD -> "r_";
        };
    }

    private static String random() {
        StringBuilder sb = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
