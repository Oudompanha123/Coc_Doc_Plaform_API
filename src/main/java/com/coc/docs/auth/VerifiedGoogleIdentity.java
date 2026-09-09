package com.coc.docs.auth;

/** What a Google ID token proved about the caller, once every check has passed. */
record VerifiedGoogleIdentity(String email, String displayName) {}
