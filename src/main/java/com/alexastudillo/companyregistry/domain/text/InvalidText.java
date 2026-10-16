package com.alexastudillo.companyregistry.domain.text;

/** Transport-neutral validation rule; API owns any field/error-envelope mapping. */
public final class InvalidText extends IllegalArgumentException {
    private final String rule;

    public InvalidText(String rule) {
        super(rule);
        this.rule = rule;
    }

    public String rule() {
        return rule;
    }
}
