package com.fintrack.api.model;

public enum Role {
    USER,
    ADMIN;

    /** Spring Security expects authorities to carry the {@code ROLE_} prefix. */
    public String authority() {
        return "ROLE_" + name();
    }
}
