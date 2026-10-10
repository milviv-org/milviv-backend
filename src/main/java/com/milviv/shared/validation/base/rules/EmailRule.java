package com.milviv.shared.validation.base.rules;

import java.util.regex.Pattern;

public class EmailRule {
  
  private static final Pattern EMAIL_PATTERN = Pattern.compile("^[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}$");

  public boolean validate(String value) {
    if (value == null || value.isBlank()) {
      return false;
    }
    return EMAIL_PATTERN.matcher(value).matches();
  }
}
