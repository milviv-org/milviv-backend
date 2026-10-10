package com.milviv.shared.validation.base.rules;

public class EnumRule {
  
  public boolean validate(Object value, Class<? extends Enum<?>> enumClass) {
    if (value == null) {
      return false;
    }
    for (Enum<?> constant : enumClass.getEnumConstants()) {
      if (constant.name().equals(value.toString())) {
        return true;
      }
    }

    return false;
  }

}
