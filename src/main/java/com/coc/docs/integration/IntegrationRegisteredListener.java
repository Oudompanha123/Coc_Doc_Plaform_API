package com.coc.docs.integration;

import com.coc.docs.account.AccountRegisteredEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Fans a new registration out to every integration subscribed to
 * {@link IntegrationEvents#ACCOUNT_REGISTERED}.
 *
 * <p>{@code AFTER_COMMIT}, same reasoning {@link com.coc.docs.notify.RegistrationNotifier}
 * already documents for the same event: a rolled-back registration must
 * announce nothing. {@code @Async} lives on {@link IntegrationService#dispatchEvent}
 * itself, not here — {@code IntegrationService} is the bean the async proxy
 * wraps, and a self-invocation from this class would bypass it if the
 * annotation were placed here instead.
 *
 * <p>The first real trigger v1 ships with (ADR-037 §5's open choice,
 * resolved this way rather than adding a new {@code page.published} event to
 * {@code PageService}) — not because a registration is the most obviously
 * useful thing to notify on, but because it is the one domain event this
 * backend already publishes.
 *
 * <p>// req: FR-INTEG-005, ADR-037
 */
@Component
public class IntegrationRegisteredListener {

    private final IntegrationService integrations;

    public IntegrationRegisteredListener(IntegrationService integrations) {
        this.integrations = integrations;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAccountRegistered(AccountRegisteredEvent event) {
        integrations.dispatchEvent(IntegrationEvents.ACCOUNT_REGISTERED);
    }
}
