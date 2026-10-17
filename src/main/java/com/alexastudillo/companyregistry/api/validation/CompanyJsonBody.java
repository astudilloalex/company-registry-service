package com.alexastudillo.companyregistry.api.validation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Opts an owned request DTO into service-specific JSON input-failure translation. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface CompanyJsonBody {}
