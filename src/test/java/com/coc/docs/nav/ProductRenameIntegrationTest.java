package com.coc.docs.nav;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coc.docs.PostgresTestBase;
import com.coc.docs.account.Account;
import com.coc.docs.account.AccountRepository;
import com.coc.docs.account.AccountStatus;
import com.coc.docs.account.AppRole;
import com.coc.docs.auth.CurrentUser;
import com.coc.docs.docscontent.DocsContentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@link NavService#updateProduct}'s slug-following rename against a real
 * PostgreSQL — the FK cascade (V79) and the atomicity of the
 * {@code nav_product}/{@code nav_item}/{@code docs_content} write are both
 * schema/transaction behaviour a mocked {@link NavServiceTest} cannot
 * exercise.
 *
 * <p>// req: product rename investigation, 2026-10-09
 */
class ProductRenameIntegrationTest extends PostgresTestBase {

    @Autowired NavService nav;
    @Autowired NavRepository repository;
    @Autowired DocsContentRepository contentRepository;
    @Autowired AccountRepository accounts;
    @Autowired JdbcClient jdbc;

    private CurrentUser editor;

    @BeforeEach
    void setUp() {
        jdbc.sql(
                        "truncate audit_log, docs_content, nav_item, nav_product_slug_history, nav_product, account"
                                + " restart identity cascade")
                .update();

        Account account =
                accounts.insert("editor@example.com", "Editor", AppRole.SUPER_ADMIN, null, AccountStatus.ACTIVE);
        editor = new CurrentUser(account, accounts.findPermissions(account.id()), new byte[] {1});

        jdbc.sql("insert into nav_product (key, label, icon, sort_order) values ('jex3-0', 'Jex3.0', 'layers', 1)")
                .update();
        jdbc.sql("insert into nav_item (product_key, slug, label, sort_order) values ('jex3-0', 'overview', 'Overview', 1)")
                .update();
        jdbc.sql(
                        "insert into docs_content (product_key, slug, kind, body) values ('jex3-0', 'overview', 'hero', ?::jsonb)")
                .param("{\"ctaHref\": \"/docs/jex3-0/get-started\", \"ctaLabel\": \"Go\"}")
                .update();
    }

    @Test
    @DisplayName("renaming the product moves nav_item (via cascade), docs_content, and its stored ctaHref onto the new key")
    void renameCascadesEverything() {
        nav.updateProduct("jex3-0", "Jex", "layers", editor);

        assertThat(repository.productExists("jex3-0")).isFalse();
        assertThat(repository.productExists("jex")).isTrue();

        Integer itemsUnderNewKey =
                jdbc.sql("select count(*) from nav_item where product_key = 'jex'").query(Integer.class).single();
        assertThat(itemsUnderNewKey).isEqualTo(1);

        String ctaHref = jdbc.sql("select body ->> 'ctaHref' from docs_content where product_key = 'jex'")
                .query(String.class)
                .single();
        assertThat(ctaHref).isEqualTo("/docs/jex/get-started");

        assertThat(repository.findCurrentKeyByOldSlug("jex3-0")).contains("jex");
    }

    @Test
    @DisplayName("a rename chain (A -> B -> C) resolves every old slug straight to C, with no intermediate hop")
    void renameChainResolvesDirectlyToTheLatestKey() {
        nav.updateProduct("jex3-0", "Jex", "layers", editor); // jex3-0 -> jex
        nav.updateProduct("jex", "Jexxy", "layers", editor); // jex -> jexxy

        assertThat(repository.findCurrentKeyByOldSlug("jex3-0")).contains("jexxy");
        assertThat(repository.findCurrentKeyByOldSlug("jex")).contains("jexxy");
        assertThat(repository.productExists("jex3-0")).isFalse();
        assertThat(repository.productExists("jex")).isFalse();
        assertThat(repository.productExists("jexxy")).isTrue();
    }

    @Test
    @DisplayName("deleting a product removes its slug history with it — nothing is left to redirect to")
    void deletingAProductRemovesItsHistory() {
        nav.updateProduct("jex3-0", "Jex", "layers", editor);
        jdbc.sql("delete from nav_item where product_key = 'jex'").update();

        nav.deleteProduct("jex", editor);

        assertThat(repository.findCurrentKeyByOldSlug("jex3-0")).isEmpty();
    }

    @Test
    @DisplayName("a failure partway through rolls back the whole rename — no half-renamed product")
    void transactionRollsBackOnAMidwayFailure() {
        // An orphan docs_content row pre-seeded at the slug the rename is
        // about to produce, under the SAME sub-item slug as the live row —
        // this forces a real primary-key violation when the rename's
        // docs_content repoint runs, partway through the transaction.
        jdbc.sql("insert into docs_content (product_key, slug, kind, body) values ('jex', 'overview', 'hero', '{}'::jsonb)")
                .update();

        assertThatThrownBy(() -> nav.updateProduct("jex3-0", "Jex", "layers", editor)).isInstanceOf(RuntimeException.class);

        assertThat(repository.productExists("jex3-0")).isTrue();
        assertThat(repository.productExists("jex")).isFalse();
        assertThat(repository.findCurrentKeyByOldSlug("jex3-0")).isEmpty();

        Integer auditRows = jdbc.sql("select count(*) from audit_log where action = 'nav.product_rename'")
                .query(Integer.class)
                .single();
        assertThat(auditRows).isEqualTo(0);
    }

    @Test
    @DisplayName("a slug conflict against a LIVE product still gets the usual -2 suffix")
    void conflictAgainstALiveProductGetsSuffixed() {
        jdbc.sql("insert into nav_product (key, label, icon, sort_order) values ('jex', 'Existing Jex', 'layers', 2)")
                .update();

        NavService.ProductRenameResult result = nav.updateProduct("jex3-0", "Jex", "layers", editor);

        assertThat(result.product().key()).isEqualTo("jex-2");
    }
}
