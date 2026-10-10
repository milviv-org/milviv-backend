package com.milviv.shared.validation.base.rules;

public class MaxLengthRule {
  
  public boolean validate(String value, Integer length) {
    if (value == null || length == null) {
      return false;
    }
    return value.length() <= length;
  }
}
