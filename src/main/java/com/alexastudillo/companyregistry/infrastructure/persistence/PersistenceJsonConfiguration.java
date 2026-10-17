package com.alexastudillo.companyregistry.infrastructure.persistence;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.quarkus.hibernate.orm.JsonFormat;
import io.quarkus.hibernate.orm.PersistenceUnitExtension;
import jakarta.enterprise.context.ApplicationScoped;
import org.hibernate.type.format.FormatMapper;
import org.hibernate.type.format.jackson.JacksonJsonFormatMapper;
import org.hibernate.type.descriptor.WrapperOptions;
import org.hibernate.type.descriptor.java.JavaType;

/** Stored JSON must not inherit HTTP null omission or envelope customization. */
@ApplicationScoped @JsonFormat @PersistenceUnitExtension
public class PersistenceJsonConfiguration implements FormatMapper {
    private final FormatMapper delegate = new JacksonJsonFormatMapper(JsonMapper.builder().addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build());

    @Override public <T> T fromString(CharSequence value, JavaType<T> type, WrapperOptions options) {
        return delegate.fromString(value, type, options);
    }
    @Override public <T> String toString(T value, JavaType<T> type, WrapperOptions options) {
        return delegate.toString(value, type, options);
    }
}
