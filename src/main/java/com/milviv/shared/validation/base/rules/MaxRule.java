package com.milviv.shared.validation.base.rules;

public class MaxRule {
  
  public boolean validate(Number value, Number num) {
    if (value == null || num == null) {
      return false;
    }
    return value.doubleValue() <= num.doubleValue();
  }
}
