package com.milviv.shared.validation.annotation;

public class AnnotationValidator implements AnnotationValidatorInterface {

    @Override
    public AnnotationObjectValidatorInterface validateByObject(Object object) {
        return new AnnotationObjectValidator(object);
    }
}
