package com.coc.docs.notify;

import static org.assertj.core.api.Assertions.assertThat;

import com.coc.docs.PostgresTestBase;
import com.coc.docs.account.Account;
import com.coc.docs.account.AccountRepository;
import com.coc.docs.account.AccountStatus;
import com.coc.docs.account.AppRole;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@link NotificationRepository#findPage} against a real PostgreSQL — the
 * row-value keyset comparison, the tie-break on {@code id}, and the filters
 * living in the query (ADR-106).
 *
 * <p>// req: FR-AUTH-008, ADR-106
 */
class NotificationRepositoryPageIntegrationTest extends PostgresTestBase {

    private static final Instant BASE = Instant.parse("2026-10-09T09:00:00Z");

    @Autowired NotificationRepository notifications;
    @Autowired AccountRepository accounts;
    @Autowired JdbcClient jdbc;

    private Account owner;
    private Account other;

    @BeforeEach
    void setUp() {
        jdbc.sql("delete from notification").update();
        owner = accounts.insert("page-owner@example.com", "Owner", AppRole.USER, null, AccountStatus.ACTIVE);
        other = accounts.insert("page-other@example.com", "Other", AppRole.USER, null, AccountStatus.ACTIVE);
    }

    private UUID insert(Account account, String type, Instant createdAt, boolean read) {
        return jdbc.sql("""
                        insert into notification (account_id, type, target_id, payload, created_at, read_at)
                        values (?, ?, 't', '{"body":"x"}'::jsonb, ?, ?)
                        returning id
                        """)
                .params(
                        account.id(),
                        type,
                        OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC),
                        read ? OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC) : null)
                .query(UUID.class)
                .single();
    }

    @Test
    @DisplayName("paging by the last row walks every row once, even through identical timestamps")
    void walksEveryRowOnce() {
        List<UUID> inserted = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            inserted.add(insert(owner, "announcement.published", BASE.minusSeconds(i * 60L), true));
        }
        // Three rows written in the same instant — only the id can order them.
        for (int i = 0; i < 3; i++) {
            inserted.add(insert(owner, "announcement.published", BASE.minusSeconds(600), true));
        }
        insert(other, "announcement.published", BASE, false);

        List<UUID> seen = new ArrayList<>();
        List<Notification> page = notifications.findPage(owner.id(), null, null, false, null, 3);
        while (!page.isEmpty()) {
            page.forEach(n -> seen.add(n.id()));
            Notification last = page.get(page.size() - 1);
            page = notifications.findPage(owner.id(), last.createdAt(), last.id(), false, null, 3);
        }

        assertThat(seen).hasSize(8).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(inserted);
    }

    @Test
    @DisplayName("the unread filter and the excluded type are applied before the limit")
    void filtersInTheQuery() {
        insert(owner, "registration.pending", BASE, false);
        UUID unread = insert(owner, "announcement.published", BASE.minusSeconds(60), false);
        insert(owner, "announcement.published", BASE.minusSeconds(120), true);

        List<Notification> page = notifications.findPage(owner.id(), null, null, true, "registration.pending", 1);

        assertThat(page).extracting(Notification::id).containsExactly(unread);
    }
}
