package com.alexastudillo.companyregistry.api.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.*;

@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = TextLengthValidator.class)
public @interface TextLength {
    int max();
    String message() default "text-too-long";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
