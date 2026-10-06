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
 * Rejects an address {@link EmailPolicy} would not accept (ADR-088).
 *
 * <p>Replaces {@code @Email} where a <em>new</em> address is accepted. The
 * built-in constraint implements RFC 5322's grammar, under which
 * {@code qqq@qqq} is valid — correct as a grammar check and wrong as a rule for
 * addresses that must receive mail.
 *
 * <p><b>Not for sign-in.</b> {@code PasswordLoginRequest} keeps plain
 * {@code @Email}: an account created under the looser rule has to be able to
 * log in, exactly as {@link StrongPassword} is kept off the same request.
 *
 * <p>// req: NFR-SEC-AUTH, ADR-088
 */
@Documented
@Constraint(validatedBy = ValidEmail.Validator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidEmail {

    String message() default "That email address is not valid.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    /** Reports every unmet rule, so the message names what to fix. */
    class Validator implements ConstraintValidator<ValidEmail, String> {

        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            // Null is @NotBlank's business; saying "enter an email" here too
            // would put the same complaint on the field twice.
            if (value == null) {
                return true;
            }
            var problems = EmailPolicy.violations(value);
            if (problems.isEmpty()) {
                return true;
            }
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(String.join(" ", problems))
                    .addConstraintViolation();
            return false;
        }
    }
}
