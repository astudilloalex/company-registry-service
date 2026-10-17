package com.alexastudillo.companyregistry.api.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class TextLengthValidator implements ConstraintValidator<TextLength, String> {
    private int max;
    @Override public void initialize(TextLength constraint) { max = constraint.max(); }
    @Override public boolean isValid(String value, ConstraintValidatorContext context) {
        return value == null || value.codePointCount(0, value.length()) <= max;
    }
}
