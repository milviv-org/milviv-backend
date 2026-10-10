package com.milviv.infrastructure.http;

import com.milviv.domain.exception.ValidationException;

public interface HttpContextValidatorInterface {
  void validate(Object object) throws ValidationException;
}
