package com.alexastudillo.companyregistry.api.context;

import com.alexastudillo.companyregistry.application.context.OperationContext;
import jakarta.enterprise.context.RequestScoped;

@RequestScoped
public class RequestOperationContext {
    private OperationContext value;
    public void set(OperationContext value) { this.value = java.util.Objects.requireNonNull(value); }
    public OperationContext require() { return java.util.Objects.requireNonNull(value, "operation-context-missing"); }
}
