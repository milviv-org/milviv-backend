package com.milviv.shared.validation.base;

public enum ValidateErrorText {
    REQUIRED("Значение должно быть обязательным"),
    MAX_LENGTH("Значение должно иметь не более %s символов"),
    MIN_LENGTH("Значение должно иметь не менее %s символов"),
    PATTERN("Не корректное значение"),
    EMAIL("Значение должно быть корретной электронной почтой"),
    MAX("Значение не должно быть больше %s"),
    MIN("Значение не длжно быть меньше %s"),
    ENUM("Не корректное значение");

    String template;

    ValidateErrorText(String message) {
        this.template = message;
    }

    public String format(Object... args) {
        if (args == null || args.length == 0) {
            return template;
        }
        return String.format(template, args);
    }
}
