package com.milviv.shared.validation.base.rules;

import java.util.Collection;

public class RequiredRule {

    public <T> boolean validate(T value) {
        if (value == null) {
            return false;
        }
        if (value instanceof String s) {
            return !s.isBlank();
        }
        if (value instanceof Collection<?> c) {
            return !c.isEmpty();
        }
        return true;
    }
}
