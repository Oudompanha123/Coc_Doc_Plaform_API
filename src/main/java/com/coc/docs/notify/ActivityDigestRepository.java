package com.coc.docs.notify;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads the digest's two inputs: what happened to pages in a window, and who
 * should hear about it.
 *
 * <p><b>Read-only.</b> Nothing here writes, and in particular nothing here
 * touches {@code audit_log}, whose append-only property is held by
 * {@code AuditRepository} being the only class that inserts into it and by the
 * schema revoking {@code update}/{@code delete} from {@code public}.
 *
 * <p><b>This class reads personal data</b> — display names, email addresses,
 * and who edited what — which is the same NFR-SEC-PII surface
 * {@code AuditRepository} documents. It is reachable only from the scheduled
 * digest, never from a controller. A route added over these methods must sit
 * behind the {@code super_admin} floor, for the same reason an audit route
 * would.
 *
 * <p>// req: FR-OPS-001, NFR-OPS-AUDIT, NFR-SEC-PII, ADR-006
 */
@Repository
public class ActivityDigestRepository {

    /**
     * Page events in the window, collapsed to one row per page.
     *
     * <p>{@code distinct on} rather than a window function or a correlated
     * subquery: Postgres-only, which this project already is (CONST-TECH-08
     * defers the <em>host</em>, not the engine), and it expresses "the latest
     * row per page" directly instead of encoding it in a rank filter.
     *
     * <p>The join to {@code account} is a left join on purpose. The audit
     * trail keeps {@code actor_email} precisely so it survives the account
     * being deleted, and a digest that dropped those rows would under-report
     * exactly the activity most worth reviewing.
     */
    private static final String ACTIVITY_SQL =
            """
            with page_events as (
              select al.target_id                                as page_id,
                     al.action                                   as action,
                     al.occurred_at                              as occurred_at,
                     coalesce(acc.display_name, al.actor_email)  as actor,
                     al.actor_email                              as actor_email
              from audit_log al
              left join account acc on acc.id = al.actor_id
              where al.target_type = 'page'
                and al.target_id is not null
                and al.occurred_at >= ?
                and al.occurred_at <  ?
            ),
            latest as (
              select distinct on (page_id) page_id, action, occurred_at, actor, actor_email
              from page_events
              order by page_id, occurred_at desc
            ),
            counts as (
              select page_id, count(*) as event_count
              from page_events
              group by page_id
            )
            select s.slug                              as section_slug,
                   p.slug                              as page_slug,
                   coalesce(t.title, p.slug)           as page_title,
                   l.occurred_at                       as last_at,
                   l.actor                             as last_actor,
                   l.actor_email                       as last_actor_email,
                   l.action                            as last_action,
                   c.event_count                       as event_count,
                   (p.deleted_at is not null)          as page_deleted,
                   exists (select 1 from page_version d
                           where d.page_id = p.id and d.status = 'draft')     as has_draft,
                   exists (select 1 from page_version b
                           where b.page_id = p.id and b.status = 'published')  as has_published
            from latest l
            join counts  c on c.page_id = l.page_id
            join page    p on p.id = l.page_id
            join section s on s.id = p.section_id
            left join lateral (
              select v.title
              from page_version v
              where v.page_id = p.id
              order by (v.status = 'published') desc, (v.locale = 'en') desc, v.updated_at desc
              limit 1
            ) t on true
            order by s."order", s.slug, l.occurred_at desc
            """;

    /**
     * Who receives the digest when no explicit recipient list is configured.
     *
     * <p>Active administrators, resolved per send rather than captured in
     * configuration, so an administrator appointed next month is included
     * without anyone remembering to edit a list — and one who is deactivated
     * stops receiving it the same day, which is the half that matters.
     */
    private static final String ADMIN_RECIPIENTS_SQL =
            """
            select distinct a.email
            from account a
            join account_role ar on ar.account_id = a.id
            where ar.role_key in ('admin', 'super_admin')
              and a.status = 'active'
              and a.deactivated_at is null
            order by a.email
            """;

    private final JdbcClient jdbc;

    public ActivityDigestRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Page activity in {@code [from, to)}.
     *
     * <p>Half-open on purpose: consecutive daily windows then tile the
     * timeline exactly, with no event reported twice and none skipped at the
     * boundary.
     */
    public List<PageActivity> pageActivityBetween(Instant from, Instant to) {
        return jdbc.sql(ACTIVITY_SQL)
                .params(
                        OffsetDateTime.ofInstant(from, ZoneOffset.UTC),
                        OffsetDateTime.ofInstant(to, ZoneOffset.UTC))
                .query(ActivityDigestRepository::map)
                .list();
    }

    /** Active admin and super_admin email addresses. */
    public List<String> adminRecipients() {
        return jdbc.sql(ADMIN_RECIPIENTS_SQL).query(String.class).list();
    }

    private static PageActivity map(ResultSet rs, int rowNum) throws SQLException {
        return new PageActivity(
                rs.getString("section_slug"),
                rs.getString("page_slug"),
                rs.getString("page_title"),
                rs.getObject("last_at", OffsetDateTime.class).toInstant(),
                rs.getString("last_actor"),
                rs.getString("last_actor_email"),
                rs.getString("last_action"),
                rs.getInt("event_count"),
                rs.getBoolean("page_deleted"),
                rs.getBoolean("has_draft"),
                rs.getBoolean("has_published"));
    }
}
