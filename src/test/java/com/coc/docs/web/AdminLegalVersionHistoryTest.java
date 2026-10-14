package com.coc.docs.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.coc.docs.account.Account;
import com.coc.docs.account.AccountRepository;
import com.coc.docs.account.AccountStatus;
import com.coc.docs.account.AppRole;
import com.coc.docs.legal.LegalPageService;
import com.coc.docs.legal.LegalPageVersion;
import com.coc.docs.legal.LegalSlug;
import com.coc.docs.page.VersionStatus;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The legal-pages Version History list: each row's publisher name and photo,
 * and "Initial version" only for the row V68 seeded with the table — never
 * for a later row whose publisher's account was deleted.
 *
 * <p>// req: ADR-101
 */
class AdminLegalVersionHistoryTest {

    private static final Instant T0 = Instant.parse("2026-10-01T00:00:00Z");

    private final LegalPageService legal = mock(LegalPageService.class);
    private final AccountRepository accounts = mock(AccountRepository.class);
    private final AdminLegalController controller = new AdminLegalController(legal, accounts);

    private static LegalPageVersion version(int days, UUID publishedBy, VersionStatus status) {
        Instant at = T0.plusSeconds(days * 86_400L);
        return new LegalPageVersion(
                UUID.randomUUID(), LegalSlug.TERMS, status, null, 1, at, publishedBy, null, at, at);
    }

    private static Account account(String name, String avatarUrl) {
        return new Account(UUID.randomUUID(), name.toLowerCase() + "@example.com", name, EnumSet.of(AppRole.ADMIN),
                avatarUrl, null, AccountStatus.ACTIVE, null, null, T0, T0, false, true);
    }

    @Test
    @DisplayName("a person's version shows their name, and an uploaded photo through the avatar route")
    void publisherNameAndUploadedPhoto() {
        Account dana = account("Dana", "https://s3.example.com/bucket/avatars/acc-1/photo.png");
        LegalPageVersion published = version(2, dana.id(), VersionStatus.PUBLISHED);
        when(legal.history(LegalSlug.TERMS)).thenReturn(List.of(published, version(0, null, VersionStatus.ARCHIVED)));
        when(accounts.findById(dana.id())).thenReturn(Optional.of(dana));

        ApiDtos.LegalVersionSummaryResponse row = controller.versions("terms").get(0);

        assertThat(row.publishedByName()).isEqualTo("Dana");
        assertThat(row.publishedByAvatarUrl()).isEqualTo("/api/v1/avatars/acc-1/photo.png");
        assertThat(row.initialVersion()).isFalse();
    }

    @Test
    @DisplayName("a Google picture is sent unchanged; no picture stays null")
    void googlePictureAndNone() {
        String google = "https://lh3.googleusercontent.com/a/ACg8ocK=s96-c";
        Account withGoogle = account("Sok", google);
        Account withNone = account("Vanna", null);
        when(legal.history(LegalSlug.TERMS)).thenReturn(List.of(
                version(3, withGoogle.id(), VersionStatus.PUBLISHED),
                version(2, withNone.id(), VersionStatus.ARCHIVED)));
        when(accounts.findById(withGoogle.id())).thenReturn(Optional.of(withGoogle));
        when(accounts.findById(withNone.id())).thenReturn(Optional.of(withNone));

        List<ApiDtos.LegalVersionSummaryResponse> rows = controller.versions("terms");

        assertThat(rows.get(0).publishedByAvatarUrl()).isEqualTo(google);
        assertThat(rows.get(1).publishedByAvatarUrl()).isNull();
        assertThat(rows.get(1).publishedByName()).isEqualTo("Vanna");
    }

    @Test
    @DisplayName("the seeded first version (no publisher) is the initial version")
    void seededRowIsInitialVersion() {
        when(legal.history(LegalSlug.TERMS)).thenReturn(List.of(version(0, null, VersionStatus.PUBLISHED)));

        ApiDtos.LegalVersionSummaryResponse row = controller.versions("terms").get(0);

        assertThat(row.initialVersion()).isTrue();
        assertThat(row.publishedByName()).isNull();
        assertThat(row.publishedByAvatarUrl()).isNull();
    }

    @Test
    @DisplayName("a later version whose publisher's account was deleted is not called the initial version")
    void deletedPublisherIsNotInitial() {
        LegalPageVersion orphaned = version(5, null, VersionStatus.PUBLISHED); // published_by set null on delete
        LegalPageVersion seeded = version(0, null, VersionStatus.ARCHIVED);
        when(legal.history(LegalSlug.TERMS)).thenReturn(List.of(orphaned, seeded)); // newest first

        List<ApiDtos.LegalVersionSummaryResponse> rows = controller.versions("terms");

        assertThat(rows.get(0).initialVersion()).isFalse();
        assertThat(rows.get(1).initialVersion()).isTrue();
    }

    @Test
    @DisplayName("an earliest version that a person published keeps their name, not 'initial version'")
    void earliestWithPublisherIsNotInitial() {
        Account dana = account("Dana", null);
        when(legal.history(LegalSlug.TERMS)).thenReturn(List.of(version(0, dana.id(), VersionStatus.PUBLISHED)));
        when(accounts.findById(dana.id())).thenReturn(Optional.of(dana));

        ApiDtos.LegalVersionSummaryResponse row = controller.versions("terms").get(0);

        assertThat(row.initialVersion()).isFalse();
        assertThat(row.publishedByName()).isEqualTo("Dana");
    }
}
