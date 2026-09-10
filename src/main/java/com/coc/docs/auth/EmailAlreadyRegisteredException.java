package com.coc.docs.auth;

/**
 * Registration refused because the email is already registered (ADR-014).
 * Safe to state plainly to the caller, unlike sign-in's generic refusal
 * (UC-004 E-3): this is the applicant checking their own attempt, not a
 * caller probing a third party's account state.
 */
public class EmailAlreadyRegisteredException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public EmailAlreadyRegisteredException() {
        super("This email is already registered.");
    }
}
