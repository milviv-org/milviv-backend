package com.milviv.shared.validation.base;

public interface BaseValidatorInterface {

    <T> boolean required(T value);

    boolean maxLength(String value, Integer length);

    boolean minLength(String value, Integer length);

    boolean max(Number value, Number num);

    boolean min(Number value, Number num);

    boolean enumValue(Object value, Class<? extends Enum<?>> enumClass);

    boolean email(String value);

    boolean pattern(String value, String pattern);
}
