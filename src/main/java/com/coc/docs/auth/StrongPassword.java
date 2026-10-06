package com.coc.docs.auth;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Rejects a password that {@link PasswordPolicy} would not accept (ADR-087).
 *
 * <p>An annotation rather than a check inside each service, so that adding a
 * fourth place a password can be set cannot quietly skip the rule: the DTO
 * either carries this or it does not, and that is visible in the one file a
 * reviewer reads.
 *
 * <p><b>Not for sign-in.</b> {@code PasswordLoginRequest} must never carry
 * this — it presents an existing password, and an account chosen under the old
 * floor has to be able to log in and then change it.
 *
 * <p>// req: NFR-SEC-AUTH, ADR-087
 */
@Documented
@Constraint(validatedBy = StrongPassword.Validator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface StrongPassword {

    String message() default "That password is not strong enough.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    /** Reports every unmet rule, not just the first. */
    class Validator implements ConstraintValidator<StrongPassword, String> {

        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            // Null is @NotBlank's business. Saying "enter a password" here as
            // well would put the same complaint on the field twice.
            if (value == null) {
                return true;
            }
            var problems = PasswordPolicy.violations(value);
            if (problems.isEmpty()) {
                return true;
            }
            // The default message names the constraint; this names what to do
            // about it, which is the only part a person can act on.
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(String.join(" ", problems))
                    .addConstraintViolation();
            return false;
        }
    }
}
