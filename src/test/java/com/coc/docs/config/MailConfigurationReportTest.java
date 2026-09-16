package com.coc.docs.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The From-rewrite check.
 *
 * <p>This is the one piece of {@link MailConfigurationReport} with a decision
 * in it, and it is the check most likely to matter: a deployment that wires up
 * Gmail and leaves {@code app.mail.from} at its placeholder default sends mail
 * signed as a domain nobody owns.
 *
 * <p>// req: FR-OPS-001
 */
class MailConfigurationReportTest {

    @Test
    @DisplayName("warns when the From domain differs from the Google account's domain")
    void warnsOnMismatch() {
        assertThat(MailConfigurationReport.senderRewriteWarning(
                        "smtp.gmail.com",
                        "docs.platform@wecambodia.com",
                        "Coc Docs Platform <noreply@cocdocsplatform.com>"))
                .isPresent()
                .get()
                .asString()
                .contains("cocdocsplatform.com")
                .contains("wecambodia.com");
    }

    @Test
    @DisplayName("says nothing when the From address is the account itself")
    void silentWhenTheyMatch() {
        assertThat(MailConfigurationReport.senderRewriteWarning(
                        "smtp.gmail.com",
                        "docs.platform@wecambodia.com",
                        "Coc Docs Platform <docs.platform@wecambodia.com>"))
                .isEmpty();
    }

    @Test
    @DisplayName("an alias in the same domain is fine — the relay case")
    void silentForAnAliasInTheSameDomain() {
        // What a Workspace relay actually does: authorised for the domain, so
        // any address in it may appear in From.
        assertThat(MailConfigurationReport.senderRewriteWarning(
                        "smtp-relay.gmail.com",
                        "relay-user@wecambodia.com",
                        "Coc Docs <noreply-docs@wecambodia.com>"))
                .isEmpty();
    }

    @Test
    @DisplayName("domain comparison ignores case")
    void ignoresCase() {
        assertThat(MailConfigurationReport.senderRewriteWarning(
                        "smtp.gmail.com", "someone@Example.com", "someone.else@EXAMPLE.com"))
                .isEmpty();
    }

    @Test
    @DisplayName("says nothing about a non-Google relay, which has its own rules")
    void silentForOtherHosts() {
        // SES, SendGrid, Mailgun and a corporate relay all decide this
        // differently, and a warning that fires on every one of them is a
        // warning people learn to ignore.
        assertThat(MailConfigurationReport.senderRewriteWarning(
                        "email-smtp.ap-southeast-1.amazonaws.com", "AKIAEXAMPLE", "noreply@wecambodia.com"))
                .isEmpty();
    }

    @Test
    @DisplayName("says nothing when there is nothing to compare")
    void silentOnIncompleteConfiguration() {
        assertThat(MailConfigurationReport.senderRewriteWarning("smtp.gmail.com", null, "a@b.com"))
                .isEmpty();
        assertThat(MailConfigurationReport.senderRewriteWarning(null, "a@b.com", "c@d.com")).isEmpty();
        assertThat(MailConfigurationReport.senderRewriteWarning("smtp.gmail.com", "no-at-sign", "a@b.com"))
                .isEmpty();
        assertThat(MailConfigurationReport.senderRewriteWarning("smtp.gmail.com", "a@b.com", ""))
                .isEmpty();
    }
}
