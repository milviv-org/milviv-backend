package com.milviv.shared.validation.complex;

public class ComplexValidator implements ComplexValidatorInterface {
  
  
  
  @Override
  public <T> ComplexObjectValidatorInterface<T> validateByObject(T obj) {
    return new ComplexObjectValidator<T>(obj);
  }

}
