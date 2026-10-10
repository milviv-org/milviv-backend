package com.milviv.infrastructure.http;

import java.util.List;

import com.milviv.domain.exception.ValidationException;
import com.milviv.shared.validation.annotation.AnnotationObjectValidatorInterface;
import com.milviv.shared.validation.annotation.AnnotationValidatorInterface;
import com.milviv.shared.validation.base.BaseValidationException;

public class HttpContextAnnotationValidator implements HttpContextValidatorInterface {

  private final AnnotationValidatorInterface annotationValidator;

  public HttpContextAnnotationValidator(AnnotationValidatorInterface annotationValidator) {
    this.annotationValidator = annotationValidator;
  }

  @Override
  public void validate(Object object) throws ValidationException {
    try {
      AnnotationObjectValidatorInterface annotationObjectValidator = annotationValidator.validateByObject(object);
      annotationObjectValidator.validate();
    } catch (BaseValidationException e) {
      List<com.milviv.domain.exception.FieldError> domainErrors = e.getErrors().stream()
          .map(err -> new com.milviv.domain.exception.FieldError(err.field(), err.message()))
          .toList();
      throw new ValidationException(domainErrors);
    } 
  }
}
