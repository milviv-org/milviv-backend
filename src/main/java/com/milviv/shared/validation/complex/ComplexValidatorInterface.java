package com.milviv.shared.validation.complex;

public interface ComplexValidatorInterface {
  <T> ComplexObjectValidatorInterface<T> validateByObject(T obj);
}
