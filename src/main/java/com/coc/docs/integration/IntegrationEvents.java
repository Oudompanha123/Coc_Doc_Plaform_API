package com.coc.docs.integration;

import java.util.Set;

/**
 * The event-type vocabulary an integration may subscribe to.
 *
 * <p>Deliberately one entry today. ADR-037 §5 left "which real event fires
 * dispatch" as an open implementation choice — add a {@code page.published}
 * entry only once {@code PageService} actually publishes that event; a
 * caller-supplied event type this dispatcher can never fire would silently
 * never deliver, which is worse than the connect form simply not offering it
 * yet (same reasoning {@code Permission.parse} gives for refusing an
 * unrecognised value rather than accepting it and doing nothing).
 *
 * <p>// req: FR-INTEG-001, ADR-037
 */
public final class IntegrationEvents {

    private IntegrationEvents() {}

    /** Fired after {@code AccountRegisteredEvent} commits — see {@link IntegrationRegisteredListener}. */
    public static final String ACCOUNT_REGISTERED = "account.registered";

    public static final Set<String> KNOWN = Set.of(ACCOUNT_REGISTERED);
}
