package com.coc.docs.account;

import java.util.UUID;

/**
 * An account row, as needed by the login/session path. Full CRUD (create,
 * deactivate, role assignment — FR-AUTH-003/004) belongs to the later
 * accounts epic (DEV-PLAN.md E8); this login API only ever reads.
 */
public record Account(UUID id, String email, String displayName, Role role, boolean deactivated) {}
