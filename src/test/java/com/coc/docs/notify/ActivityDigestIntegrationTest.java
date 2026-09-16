package com.coc.docs.notify;

import static org.assertj.core.api.Assertions.assertThat;

import com.coc.docs.PostgresTestBase;
import com.coc.docs.account.Account;
import com.coc.docs.account.AccountRepository;
import com.coc.docs.account.AccountStatus;
import com.coc.docs.account.AppRole;
import com.coc.docs.audit.AuditAction;
import com.coc.docs.page.Page;
import com.coc.docs.page.PageRepository;
import com.coc.docs.section.Section;
import com.coc.docs.section.SectionRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The digest against a real PostgreSQL — the query is where this feature can
 * actually be wrong. {@code distinct on}, the half-open window, the lateral
 * title lookup and the draft/published existence checks all behave differently
 * or not at all outside Postgres, which is why this is not a unit test with a
 * stubbed repository.
 *
 * <p>Sending is not exercised here: with no {@code spring.mail.host} in the
 * test profile there is no {@code JavaMailSender} bean, which is the same
 * shape production takes before SMTP is configured, and
 * {@link NotificationMailServiceTest} covers the send decisions directly.
 *
 * <p>// req: FR-OPS-001, NFR-OPS-AUDIT
 */
class ActivityDigestIntegrationTest extends PostgresTestBase {

    @Autowired ActivityDigestRepository digestRepository;
    @Autowired ActivityDigestService digestService;
    @Autowired NotificationProperties properties;
    @Autowired AccountRepository accounts;
    @Autowired SectionRepository sections;
    @Autowired PageRepository pages;
    @Autowired JdbcClient jdbc;

    private static final LocalDate DAY = LocalDate.of(2026, 9, 14);

    private ZoneId zone;
    private Account editor;
    private Section jex;
    private Section guides;

    @BeforeEach
    void setUp() {
        jdbc.sql("truncate audit_log, app_session, page_version, page_slug_history, page, "
                        + "section, account restart identity cascade")
                .update();

        zone = properties.zoneId();
        editor = accounts.insert("editor@example.com", "Sok Dara", AppRole.ADMIN, null, AccountStatus.ACTIVE);
        jex = sections.insert("jex2.0", 2, true);
        guides = sections.insert("guides", 3, true);
    }

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

    private Instant at(LocalDate day, int hour, int minute) {
        return day.atTime(LocalTime.of(hour, minute)).atZone(zone).toInstant();
    }

    private void audit(Page page, String action, Instant occurredAt) {
        jdbc.sql("""
                        insert into audit_log
                          (actor_id, actor_email, action, target_type, target_id, occurred_at)
                        values (?, ?, ?, 'page', ?, ?)
                        """)
                .params(
                        editor.id(),
                        editor.email(),
                        action,
                        page.id(),
                        OffsetDateTime.ofInstant(occurredAt, ZoneOffset.UTC))
                .update();
    }

    private void version(Page page, String status, String title) {
        jdbc.sql("""
                        insert into page_version (page_id, locale, status, title, body)
                        values (?, 'en', ?::version_status, ?, '[]'::jsonb)
                        """)
                .params(page.id(), status, title)
                .update();
    }

    private List<PageActivity> activityForDay() {
        return digestRepository.pageActivityBetween(
                DAY.atStartOfDay(zone).toInstant(), DAY.plusDays(1).atStartOfDay(zone).toInstant());
    }

    private static PageActivity find(List<PageActivity> activity, String slug) {
        return activity.stream()
                .filter(a -> a.pageSlug().equals(slug))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no activity row for page " + slug));
    }

    // -----------------------------------------------------------------------
    // tests
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("one row per page, carrying the latest event and the page's current state")
    void collapsesEventsToOneRowPerPage() {
        Page page = pages.insert(jex.id(), "getting-started", 1, null, AppRole.USER);
        version(page, "published", "Getting Started");

        audit(page, AuditAction.PAGE_DRAFT_SAVE, at(DAY, 9, 12));
        audit(page, AuditAction.PAGE_DRAFT_SAVE, at(DAY, 10, 30));
        audit(page, AuditAction.PAGE_PUBLISH, at(DAY, 11, 5));

        List<PageActivity> activity = activityForDay();

        assertThat(activity).hasSize(1);
        PageActivity row = activity.get(0);
        assertThat(row.sectionSlug()).isEqualTo("jex2.0");
        assertThat(row.pageTitle()).isEqualTo("Getting Started");
        assertThat(row.lastAction()).isEqualTo(AuditAction.PAGE_PUBLISH);
        assertThat(row.lastAt()).isEqualTo(at(DAY, 11, 5));
        assertThat(row.eventCount()).isEqualTo(3);
        // The display name, not the denormalised email, where the account still exists.
        assertThat(row.lastActor()).isEqualTo("Sok Dara");
        assertThat(row.lastActorEmail()).isEqualTo("editor@example.com");
        assertThat(row.hasPublished()).isTrue();
        assertThat(row.hasDraft()).isFalse();
        assertThat(row.pageDeleted()).isFalse();
    }

    @Test
    @DisplayName("the window is half-open: the following day's first event is not counted")
    void windowIsHalfOpen() {
        Page page = pages.insert(jex.id(), "usage", 1, null, AppRole.USER);
        version(page, "published", "Usage");

        audit(page, AuditAction.PAGE_DRAFT_SAVE, at(DAY.minusDays(1), 23, 59));
        audit(page, AuditAction.PAGE_PUBLISH, at(DAY, 0, 0)); // first instant — included
        audit(page, AuditAction.PAGE_DRAFT_SAVE, at(DAY.plusDays(1), 0, 0)); // next day — excluded

        PageActivity row = find(activityForDay(), "usage");
        assertThat(row.eventCount()).isEqualTo(1);
        assertThat(row.lastAction()).isEqualTo(AuditAction.PAGE_PUBLISH);
    }

    @Test
    @DisplayName("a page with an unpublished draft is reported as needing attention")
    void flagsPendingDraft() {
        Page page = pages.insert(jex.id(), "api-keys", 2, null, AppRole.USER);
        version(page, "published", "API Keys");
        version(page, "draft", "API Keys");
        audit(page, AuditAction.PAGE_DRAFT_SAVE, at(DAY, 14, 40));

        ReportMail report = digestService.buildReport(DAY, activityForDay(), zone);

        ReportMail.Row row = report.sections().get(0).rows().get(0);
        assertThat(row.cells().get(0).text()).isEqualTo("API Keys");
        // Who did it, named twice: two colleagues sharing a first name are the
        // ordinary case, and the address is what identifies them.
        assertThat(row.cells().get(2).text()).isEqualTo("Sok Dara");
        assertThat(row.cells().get(3).text()).isEqualTo("editor@example.com");
        assertThat(row.cells().get(4).text()).isEqualTo("Draft pending");
        assertThat(row.cells().get(4).emphasis()).isEqualTo(ReportMail.Emphasis.ALERT);
    }

    @Test
    @DisplayName("a soft-deleted page is reported as deleted, not as edited")
    void flagsDeletedPage() {
        Page page = pages.insert(jex.id(), "old", 3, null, AppRole.USER);
        version(page, "published", "Old Page");
        audit(page, AuditAction.PAGE_DELETE, at(DAY, 16, 0));
        jdbc.sql("update page set deleted_at = now() where id = ?").param(page.id()).update();

        ReportMail report = digestService.buildReport(DAY, activityForDay(), zone);

        ReportMail.Row row = report.sections().get(0).rows().get(0);
        assertThat(row.cells().get(4).text()).isEqualTo("Deleted");
        assertThat(row.cells().get(4).emphasis()).isEqualTo(ReportMail.Emphasis.ALERT);
    }

    @Test
    @DisplayName("a published page whose last event was the publish reads as ordinary")
    void publishedPageIsNotHighlighted() {
        Page page = pages.insert(jex.id(), "install", 4, null, AppRole.USER);
        version(page, "published", "Install");
        audit(page, AuditAction.PAGE_PUBLISH, at(DAY, 9, 0));

        ReportMail.Row row = digestService.buildReport(DAY, activityForDay(), zone)
                .sections()
                .get(0)
                .rows()
                .get(0);

        assertThat(row.cells().get(4).text()).isEqualTo("Published");
        assertThat(row.cells().get(4).emphasis()).isEqualTo(ReportMail.Emphasis.NORMAL);
    }

    @Test
    @DisplayName("rows are grouped by section, in the sections' display order")
    void groupsBySection() {
        Page inJex = pages.insert(jex.id(), "overview", 1, null, AppRole.USER);
        Page inGuides = pages.insert(guides.id(), "install", 1, null, AppRole.USER);
        version(inJex, "published", "Overview");
        version(inGuides, "published", "Install");
        audit(inGuides, AuditAction.PAGE_PUBLISH, at(DAY, 11, 2));
        audit(inJex, AuditAction.PAGE_PUBLISH, at(DAY, 9, 12));

        ReportMail report = digestService.buildReport(DAY, activityForDay(), zone);

        assertThat(report.sections()).hasSize(2);
        // section."order": jex2.0 is 2, guides is 3.
        assertThat(report.sections().get(0).heading()).isEqualTo("jex2.0");
        assertThat(report.sections().get(1).heading()).isEqualTo("guides");
        assertThat(report.rowCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("an event whose actor account is gone still appears, attributed to the audited email")
    void survivesDeletedActor() {
        Page page = pages.insert(jex.id(), "orphaned", 5, null, AppRole.USER);
        version(page, "published", "Orphaned");
        audit(page, AuditAction.PAGE_PUBLISH, at(DAY, 12, 0));

        // The last-active-super_admin guard fires on any account delete, so the
        // account being removed here cannot be the only one left.
        accounts.insert("boss@example.com", "Boss", AppRole.SUPER_ADMIN, null, AccountStatus.ACTIVE);

        // account.id is set null on the audit row by the schema's on delete set null.
        UUID editorId = editor.id();
        jdbc.sql("delete from account_role where account_id = ?").param(editorId).update();
        jdbc.sql("delete from account where id = ?").param(editorId).update();

        PageActivity row = find(activityForDay(), "orphaned");
        // Both fall back to the audit trail's denormalised address, which is
        // the whole reason that column exists.
        assertThat(row.lastActor()).isEqualTo("editor@example.com");
        assertThat(row.lastActorEmail()).isEqualTo("editor@example.com");
    }

    @Test
    @DisplayName("a day with no page activity produces an empty report")
    void emptyDay() {
        ReportMail report = digestService.buildReport(DAY, activityForDay(), zone);

        assertThat(report.isEmpty()).isTrue();
        assertThat(report.subtitle()).contains("no pages changed");
    }

    @Test
    @DisplayName("non-page audit events are ignored")
    void ignoresNonPageEvents() {
        jdbc.sql("""
                        insert into audit_log
                          (actor_id, actor_email, action, target_type, target_id, occurred_at)
                        values (?, ?, ?, 'account', ?, ?)
                        """)
                .params(
                        editor.id(),
                        editor.email(),
                        AuditAction.ACCOUNT_ROLE_CHANGE,
                        editor.id(),
                        OffsetDateTime.ofInstant(at(DAY, 10, 0), ZoneOffset.UTC))
                .update();

        assertThat(activityForDay()).isEmpty();
    }

    @Test
    @DisplayName("the default recipients are the active admins, and only those")
    void resolvesAdminRecipients() {
        accounts.insert("reader@example.com", "Reader", AppRole.USER, null, AccountStatus.ACTIVE);
        accounts.insert("pending@example.com", "Pending", AppRole.ADMIN, null, AccountStatus.PENDING);
        // Created before the deactivation below: the last-active-super_admin
        // guard is checked at commit, and this test's statements each commit
        // on their own.
        accounts.insert("boss@example.com", "Boss", AppRole.SUPER_ADMIN, null, AccountStatus.ACTIVE);
        Account deactivated =
                accounts.insert("gone@example.com", "Gone", AppRole.ADMIN, null, AccountStatus.ACTIVE);
        accounts.setDeactivated(deactivated.id(), true);

        assertThat(digestRepository.adminRecipients())
                .containsExactlyInAnyOrder("editor@example.com", "boss@example.com");
    }
}
