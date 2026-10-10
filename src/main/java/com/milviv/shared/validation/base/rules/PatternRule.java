package com.milviv.shared.validation.base.rules;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

public class PatternRule {

    // Максимальное количество уникальных паттернов в кэше.
    // При превышении — самый давно неиспользуемый паттерн удаляется (LRU).
    private static final int MAX_CACHE_SIZE = 256;

    // LRU-кэш скомпилированных regex паттернов.
    // Pattern.compile() — дорогая операция, поэтому результат кэшируется.
    //
    // LinkedHashMap(capacity, loadFactor, accessOrder=true):
    //   - accessOrder=true включает LRU-режим: при обращении к элементу
    //     он перемещается в конец, самые старые неиспользуемые — в начало
    //   - 0.75f — стандартный load factor, требуется конструктором
    //
    // Collections.synchronizedMap обеспечивает thread-safety.
    private static final Map<String, Pattern> CACHE = Collections.synchronizedMap(
        new LinkedHashMap<>(MAX_CACHE_SIZE, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Pattern> eldest) {
                // Удаляем самую старую запись как только превышен лимит
                return size() > MAX_CACHE_SIZE;
            }
        }
    );

    public boolean validate(String value, String patternStr) {
        if (value == null || patternStr == null) {
            return false;
        }
        Pattern pattern = CACHE.computeIfAbsent(patternStr, Pattern::compile);
        return pattern.matcher(value).matches();
    }
}
