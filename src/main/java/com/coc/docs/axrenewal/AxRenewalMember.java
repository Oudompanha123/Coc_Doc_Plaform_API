package com.coc.docs.axrenewal;

import java.time.Instant;
import java.util.UUID;

/**
 * One member of an AX Renewal project (ADR-069) — someone who may change it.
 *
 * <p>{@code displayName} and nothing else identifying: the member list is read
 * by everyone who can read the project, so it carries exactly what a comment
 * already shows about its author (ADR-045) — never the email address
 * (NFR-SEC-PII).
 */
public record AxRenewalMember(UUID projectId, UUID accountId, String displayName, Instant addedAt) {}
