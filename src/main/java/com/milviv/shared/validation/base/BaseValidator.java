package com.milviv.shared.validation.base;

import com.milviv.shared.validation.base.rules.EmailRule;
import com.milviv.shared.validation.base.rules.EnumRule;
import com.milviv.shared.validation.base.rules.MaxLengthRule;
import com.milviv.shared.validation.base.rules.MaxRule;
import com.milviv.shared.validation.base.rules.MinLengthRule;
import com.milviv.shared.validation.base.rules.MinRule;
import com.milviv.shared.validation.base.rules.PatternRule;
import com.milviv.shared.validation.base.rules.RequiredRule;

public class BaseValidator implements BaseValidatorInterface {

    private final RequiredRule requiredRule = new RequiredRule();
    private final EmailRule emailRule = new EmailRule();
    private final EnumRule enumRule = new EnumRule();
    private final MaxLengthRule maxLengthRule = new MaxLengthRule();
    private final MinLengthRule minLengthRule = new MinLengthRule();
    private final MaxRule maxRule = new MaxRule();
    private final MinRule minRule = new MinRule();
    private final PatternRule patternRule = new PatternRule();

    @Override
    public <T> boolean required(T value) {
        return requiredRule.validate(value);
    }

    @Override
    public boolean email(String value) {
      return emailRule.validate(value);
    }

    @Override
    public boolean enumValue(Object value, Class<? extends Enum<?>> enumClass) {
      return enumRule.validate(value, enumClass);
    } 

    @Override
    public boolean maxLength(String value, Integer length) {
      return maxLengthRule.validate(value, length);
    }

    @Override
    public boolean minLength(String value, Integer length) {
      return minLengthRule.validate(value, length);
    }

    @Override
    public boolean max(Number value, Number num) {
      return maxRule.validate(value, num);
    }

    @Override
    public boolean min(Number value, Number num) {
      return minRule.validate(value, num);
    }

    @Override
    public boolean pattern(String value, String pattern) {
      return patternRule.validate(value, pattern);
    }
}
