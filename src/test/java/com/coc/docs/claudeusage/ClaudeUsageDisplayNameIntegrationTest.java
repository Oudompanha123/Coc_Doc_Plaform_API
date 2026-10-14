package com.coc.docs.claudeusage;

import static org.assertj.core.api.Assertions.assertThat;

import com.coc.docs.PostgresTestBase;
import com.coc.docs.account.Account;
import com.coc.docs.account.AccountRepository;
import com.coc.docs.account.AccountStatus;
import com.coc.docs.account.AppRole;
import com.coc.docs.auth.CurrentUser;
import com.coc.docs.auth.SessionService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A rename on Settings → Account shows on Claude Usage (the admin dashboard,
 * the personal page and the scan logs), because the name is read from the
 * platform account at query time rather than from the roster's own copy —
 * which the reporter overwrites with its saved login name on every upload.
 * Against real Postgres, because the rule lives in the SQL.
 *
 * <p>// req: FR-USAGE-001, FR-USAGE-005, FR-AUTH-005
 */
class ClaudeUsageDisplayNameIntegrationTest extends PostgresTestBase {

    @Autowired AccountRepository accounts;
    @Autowired SessionService sessions;
    @Autowired ClaudeUsageService usage;
    @Autowired ClaudeUsageMemberRepository members;
    @Autowired ClaudeUsageScanLogRepository scanLogs;

    private static String email(String who) {
        return who + "-" + UUID.randomUUID().toString().substring(0, 8) + "@cocdocs.example";
    }

    private static LocalDate today() {
        return LocalDate.now(ZoneId.of("Asia/Phnom_Penh"));
    }

    /** A usage upload, as the reporter sends it: with the display name it saved at `claude-team-usage login`. */
    private void report(String email, String cliName, long output) {
        usage.recordUsage(email, cliName, today(), 10, output, 0, 0, List.of("claude-opus-5-5"), Map.of());
    }

    private static CurrentUser caller(Account account) {
        return new CurrentUser(account, Set.of(), new byte[0]);
    }

    private ClaudeUsageMemberUsage rowFor(String email) {
        return usage.dashboard("week", 0).members().stream()
                .filter(m -> m.member().email().equals(email))
                .findFirst()
                .orElseThrow();
    }

    @Test
    @DisplayName("a rename shows on the admin dashboard and the personal page, and the usage stays with the same member")
    void renameShowsEverywhere() {
        String email = email("dana");
        Account dana = accounts.insert(email, "Dana", AppRole.USER, null, AccountStatus.ACTIVE);
        report(email, "Dana", 500);
        UUID memberId = members.findByEmail(email).orElseThrow().id();

        sessions.renameSelf(caller(dana), "Dana Kim");

        ClaudeUsageMemberUsage row = rowFor(email);
        assertThat(row.member().displayName()).isEqualTo("Dana Kim");
        assertThat(row.member().id()).isEqualTo(memberId);
        assertThat(row.outputTokens()).isEqualTo(500);
        assertThat(usage.myDashboard(caller(accounts.findById(dana.id()).orElseThrow()), 7).displayName())
                .isEqualTo("Dana Kim");
        assertThat(members.findById(memberId).orElseThrow().displayName()).isEqualTo("Dana Kim");
    }

    @Test
    @DisplayName("the reporter's next upload, still carrying the old name, does not bring it back")
    void staleReporterNameDoesNotRevert() {
        String email = email("sok");
        Account sok = accounts.insert(email, "Sok", AppRole.USER, null, AccountStatus.ACTIVE);
        report(email, "Sok", 100);
        sessions.renameSelf(caller(sok), "Sok Dara");

        report(email, "Sok", 300); // the CLI's saved login name is the old one

        ClaudeUsageMemberUsage row = rowFor(email);
        assertThat(row.member().displayName()).isEqualTo("Sok Dara");
        assertThat(row.outputTokens()).isEqualTo(300); // the day was replaced, not duplicated
    }

    @Test
    @DisplayName("an unchanged name still shows as it was")
    void unchangedName() {
        String email = email("vanna");
        accounts.insert(email, "Vanna", AppRole.USER, null, AccountStatus.ACTIVE);
        report(email, "Vanna", 50);

        assertThat(rowFor(email).member().displayName()).isEqualTo("Vanna");
    }

    @Test
    @DisplayName("an email with no platform account keeps the name its reporter sent")
    void noAccountFallsBackToReportedName() {
        String email = email("contractor");
        report(email, "Contractor PC", 50);
        report(email, "Contractor Laptop", 60); // last write wins for the stored copy

        assertThat(rowFor(email).member().displayName()).isEqualTo("Contractor Laptop");
    }

    @Test
    @DisplayName("two members each keep their own name and usage")
    void namesStayWithTheRightPerson() {
        String a = email("alpha");
        String b = email("bravo");
        Account alpha = accounts.insert(a, "Alpha", AppRole.USER, null, AccountStatus.ACTIVE);
        accounts.insert(b, "Bravo", AppRole.USER, null, AccountStatus.ACTIVE);
        report(a, "Alpha", 111);
        report(b, "Bravo", 222);

        sessions.renameSelf(caller(alpha), "Alpha Renamed");

        assertThat(rowFor(a).member().displayName()).isEqualTo("Alpha Renamed");
        assertThat(rowFor(a).outputTokens()).isEqualTo(111);
        assertThat(rowFor(b).member().displayName()).isEqualTo("Bravo");
        assertThat(rowFor(b).outputTokens()).isEqualTo(222);
    }

    @Test
    @DisplayName("the scan logs show the renamed name too, even on scans recorded before the rename")
    void scanLogsShowTheNewName() {
        String email = email("lina");
        Account lina = accounts.insert(email, "Lina", AppRole.USER, null, AccountStatus.ACTIVE);
        report(email, "Lina", 10);
        UUID memberId = members.findByEmail(email).orElseThrow().id();
        Instant started = Instant.now().minusSeconds(30);
        scanLogs.insert(new ClaudeUsageScanLog(null, UUID.randomUUID(), memberId, email, null, "manual", "0.6.0",
                started, started.plusSeconds(5), started, "success", "uploaded", 1, 1, 0, 10L, 1, null, null,
                List.of()), "[]");

        sessions.renameSelf(caller(lina), "Lina Chan");

        assertThat(scanLogs.list(started.minusSeconds(60), started.plusSeconds(60), 100))
                .filteredOn(l -> l.email().equals(email))
                .extracting(ClaudeUsageScanLog::displayName)
                .containsExactly("Lina Chan");
    }
}
