package com.coc.docs.nav;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.coc.docs.account.AppRole;
import com.coc.docs.audit.AuditRepository;
import com.coc.docs.auth.CurrentUser;
import com.coc.docs.auth.TestCallers;
import com.coc.docs.docscontent.DocsContentRepository;
import com.coc.docs.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * The removal-safety rule this table exists to enforce (confirmed 2026-09-15:
 * block rather than orphan or archive), plus the key/slug validation that
 * keeps a created product from colliding with an existing static route.
 *
 * <p>Mocked rather than run against a database, same reasoning as {@code
 * NotificationServiceTest}: every claim here is a decision this class makes,
 * not a query the repository could get wrong.
 */
class NavServiceTest {

    private final NavRepository repository = mock(NavRepository.class);
    private final NavGroupRepository groupRepository = mock(NavGroupRepository.class);
    private final DocsContentRepository contentRepository = mock(DocsContentRepository.class);
    private final AuditRepository audit = mock(AuditRepository.class);
    private final NavService service =
            new NavService(repository, groupRepository, contentRepository, audit, new ObjectMapper());

    private static final CurrentUser EDITOR = TestCallers.user(AppRole.ADMIN);

    @Test
    @DisplayName("refuses to delete a product that still has sub-items")
    void blocksProductDeletionWithItems() {
        when(repository.productExists("jex3-0")).thenReturn(true);
        when(repository.itemCountForProduct("jex3-0")).thenReturn(3);

        assertThatThrownBy(() -> service.deleteProduct("jex3-0", EDITOR))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).status())
                .isEqualTo(HttpStatus.CONFLICT);

        verify(repository, never()).deleteProduct(any());
    }

    @Test
    @DisplayName("deletes a product once it has no sub-items left")
    void deletesEmptyProduct() {
        when(repository.productExists("jex3-0")).thenReturn(true);
        when(repository.itemCountForProduct("jex3-0")).thenReturn(0);

        service.deleteProduct("jex3-0", EDITOR);

        verify(repository).deleteProduct("jex3-0");
    }

    @Test
    @DisplayName("deleting a sub-item also deletes its saved content, if any")
    void deletingItemCascadesToItsContent() {
        when(repository.itemExists("jex3-0", "overview")).thenReturn(true);

        service.deleteItem("jex3-0", "overview", EDITOR);

        verify(contentRepository).delete("jex3-0", "overview");
        verify(repository).deleteItem("jex3-0", "overview");
    }

    @Test
    @DisplayName("deletes a sub-item that never had saved content")
    void deletesItemWithNoContent() {
        when(repository.itemExists("jex3-0", "prerequisites")).thenReturn(true);

        service.deleteItem("jex3-0", "prerequisites", EDITOR);

        verify(contentRepository).delete("jex3-0", "prerequisites");
        verify(repository).deleteItem("jex3-0", "prerequisites");
    }

    @Test
    @DisplayName("refuses a product key that already names a static route")
    void refusesReservedKey() {
        assertThatThrownBy(() -> service.createProduct("settings", "Settings", "layers", EDITOR))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).status())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        verify(repository, never()).insertProduct(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("refuses every static route added after the reserved list was first written")
    void refusesLaterStaticRoutes() {
        // A product keyed "ax-renewal" was created and its sidebar link opened
        // the AX Renewal workspace instead — app/ax-renewal shadows [productKey].
        for (String key : List.of("ax-renewal", "attendance", "home", "meeting", "my-claude-usage")) {
            assertThatThrownBy(() -> service.createProduct(key, "X", "layers", EDITOR))
                    .as(key)
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).status())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }

        verify(repository, never()).insertProduct(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("refuses a product key that is not kebab-case")
    void refusesMalformedKey() {
        assertThatThrownBy(() -> service.createProduct("Not Valid!", "X", "layers", EDITOR))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("refuses a duplicate product key")
    void refusesDuplicateKey() {
        when(repository.productExists("coach")).thenReturn(true);

        assertThatThrownBy(() -> service.createProduct("coach", "Coach", "person", EDITOR))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).status())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("refuses a key that was a previous product's URL (retired into slug history) — the hijack-prevention rule")
    void refusesRetiredSlugAsANewProductKey() {
        when(repository.productExists("jex3-0")).thenReturn(false);
        when(repository.slugEverUsed("jex3-0")).thenReturn(true);

        assertThatThrownBy(() -> service.createProduct("jex3-0", "Jex3.0 Again", "layers", EDITOR))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).status())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        verify(repository, never()).insertProduct(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("accepts a well-formed, unreserved, unique key")
    void createsProduct() {
        when(repository.productExists("newthing")).thenReturn(false);
        when(repository.nextProductOrder()).thenReturn(6);

        NavProduct created = service.createProduct("newthing", "New Thing", "layers", EDITOR);

        assertThat(created.key()).isEqualTo("newthing");
        assertThat(created.sortOrder()).isEqualTo(6);
        verify(repository).insertProduct("newthing", "New Thing", "layers", 6);
    }

    @Test
    @DisplayName("refuses a reorder whose key set does not match the current products")
    void refusesMismatchedReorder() {
        when(repository.findAllWithItems())
                .thenReturn(List.of(new NavProduct("jex3-0", "Jex3.0", "layers", 1, "user", List.of())));

        assertThatThrownBy(() -> service.reorderProducts(List.of("jex3-0", "made-up")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).status())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        verify(repository, never()).reorderProducts(any());
    }

    @Nested
    @DisplayName("item overrides (sidebar kebab menu: rename/move)")
    class ItemOverrides {

        @Test
        @DisplayName("refuses an item key that isn't one of the five ADMINISTRATION-block items")
        void refusesUnknownItemKey() {
            assertThatThrownBy(() -> service.upsertItemOverride("/made-up", "New Label", null, EDITOR))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).status())
                    .isEqualTo(HttpStatus.NOT_FOUND);

            verify(groupRepository, never()).upsertItemOverride(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.anyBoolean(), any());
        }

        @Test
        @DisplayName("refuses an update with neither a label nor a group")
        void refusesEmptyUpdate() {
            assertThatThrownBy(() -> service.upsertItemOverride("/legal-pages", null, null, EDITOR))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).status())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("refuses a label over 30 characters")
        void refusesOverlongLabel() {
            assertThatThrownBy(() -> service.upsertItemOverride("/legal-pages", "x".repeat(31), null, EDITOR))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).status())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("refuses a move to a group that does not exist")
        void refusesMoveToMissingGroup() {
            UUID groupId = UUID.randomUUID();
            when(groupRepository.findGroup(groupId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.upsertItemOverride("/legal-pages", null, groupId, EDITOR))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).status())
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("a rename passes only the label through, leaving the group untouched")
        void renamePassesOnlyLabel() {
            when(groupRepository.upsertItemOverride("/legal-pages", "Legal", null, null, true, false, EDITOR.id()))
                    .thenReturn(new NavItemOverride("/legal-pages", "Legal", null, null));

            service.upsertItemOverride("/legal-pages", "Legal", null, EDITOR);

            verify(groupRepository).upsertItemOverride("/legal-pages", "Legal", null, null, true, false, EDITOR.id());
        }

        @Test
        @DisplayName("a move appends to the end of the target group and passes only the group through")
        void movePassesOnlyGroupAndAppendsToTheEnd() {
            UUID groupId = UUID.randomUUID();
            when(groupRepository.findGroup(groupId)).thenReturn(Optional.of(new NavGroup(groupId, "DEVELOPER", true, 2)));
            when(groupRepository.nextSortOrderInGroup(groupId)).thenReturn(3);
            when(groupRepository.upsertItemOverride("/legal-pages", null, groupId, 3, false, true, EDITOR.id()))
                    .thenReturn(new NavItemOverride("/legal-pages", null, groupId, 3));

            service.upsertItemOverride("/legal-pages", null, groupId, EDITOR);

            verify(groupRepository).upsertItemOverride("/legal-pages", null, groupId, 3, false, true, EDITOR.id());
        }
    }

    @Nested
    @DisplayName("product rename (slug follows the name)")
    class ProductRename {

        private NavRepository.ProductSnapshot snapshot(String key) {
            return new NavRepository.ProductSnapshot(key, "whatever it was", "layers", 1, "user");
        }

        @Test
        @DisplayName("404s on a product that doesn't exist")
        void refusesMissingProduct() {
            when(repository.findProduct("made-up")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.updateProduct("made-up", "X", "layers", EDITOR))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).status())
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("a label that slugifies to the current key changes the label only — no slug write at all")
        void sameSlugChangesLabelOnly() {
            when(repository.findProduct("jex3-0")).thenReturn(Optional.of(snapshot("jex3-0")));

            NavService.ProductRenameResult result = service.updateProduct("jex3-0", "Jex3.0", "layers", EDITOR);

            assertThat(result.previousKey()).isEqualTo("jex3-0");
            assertThat(result.product().key()).isEqualTo("jex3-0");
            verify(repository).updateProduct("jex3-0", "Jex3.0", "layers");
            verify(repository, never()).insertSlugHistory(any(), any());
            verify(repository, never()).renameProductKey(any(), any(), any(), any());
            verify(contentRepository, never()).repointProductKey(any(), any());
        }

        @Test
        @DisplayName("spaces and punctuation collapse to single hyphens, trimmed at both ends")
        void slugifiesPunctuationAndSpaces() {
            when(repository.findProduct("jex3-0")).thenReturn(Optional.of(snapshot("jex3-0")));
            when(repository.slugEverUsed("jex")).thenReturn(false);

            NavService.ProductRenameResult result = service.updateProduct("jex3-0", "  Jex!! ", "layers", EDITOR);

            assertThat(result.product().key()).isEqualTo("jex");
            verify(repository).renameProductKey("jex3-0", "jex", "  Jex!! ", "layers");
        }

        @Test
        @DisplayName("a fresh, unused slug is taken as-is, with the old one recorded in history")
        void renameToAFreshSlug() {
            when(repository.findProduct("jex3-0")).thenReturn(Optional.of(snapshot("jex3-0")));
            when(repository.slugEverUsed("jex")).thenReturn(false);

            service.updateProduct("jex3-0", "Jex", "layers", EDITOR);

            verify(repository).insertSlugHistory("jex3-0", "jex3-0");
            verify(repository).renameProductKey("jex3-0", "jex", "Jex", "layers");
            verify(contentRepository).repointProductKey("jex3-0", "jex");
            verify(contentRepository).rewriteCtaHrefPrefix("jex3-0", "jex");
        }

        @Test
        @DisplayName("a slug already live on another product gets -2, then -3 if that's taken too")
        void conflictingSlugGetsNumberedSuffix() {
            when(repository.findProduct("jex3-0")).thenReturn(Optional.of(snapshot("jex3-0")));
            when(repository.slugEverUsed("jex")).thenReturn(true);
            when(repository.slugEverUsed("jex-2")).thenReturn(true);
            when(repository.slugEverUsed("jex-3")).thenReturn(false);

            NavService.ProductRenameResult result = service.updateProduct("jex3-0", "Jex", "layers", EDITOR);

            assertThat(result.product().key()).isEqualTo("jex-3");
        }

        @Test
        @DisplayName("a slug only ever retired (not currently live) still counts as taken — the hijack-prevention rule")
        void retiredSlugStillCountsAsTaken() {
            // slugEverUsed covers both nav_product.key AND nav_product_slug_history —
            // this just documents that from the service's point of view a single
            // boolean answers "taken", however the repository arrived at it.
            when(repository.findProduct("jex3-0")).thenReturn(Optional.of(snapshot("jex3-0")));
            when(repository.slugEverUsed("jex")).thenReturn(true);
            when(repository.slugEverUsed("jex-2")).thenReturn(false);

            NavService.ProductRenameResult result = service.updateProduct("jex3-0", "Jex", "layers", EDITOR);

            assertThat(result.product().key()).isEqualTo("jex-2");
        }

        @Test
        @DisplayName("a slug colliding with a static route gets -docs appended")
        void reservedSlugGetsDocsSuffix() {
            when(repository.findProduct("jex3-0")).thenReturn(Optional.of(snapshot("jex3-0")));
            when(repository.slugEverUsed("settings-docs")).thenReturn(false);

            NavService.ProductRenameResult result = service.updateProduct("jex3-0", "Settings", "layers", EDITOR);

            assertThat(result.product().key()).isEqualTo("settings-docs");
        }

        @Test
        @DisplayName("a label with no alphanumeric characters at all falls back to \"product\" rather than an empty key")
        void blankSlugFallsBack() {
            when(repository.findProduct("jex3-0")).thenReturn(Optional.of(snapshot("jex3-0")));
            when(repository.slugEverUsed("product")).thenReturn(false);

            NavService.ProductRenameResult result = service.updateProduct("jex3-0", "!!!", "layers", EDITOR);

            assertThat(result.product().key()).isEqualTo("product");
        }

        @Test
        @DisplayName("a slug is capped at 60 characters")
        void slugIsCappedAtSixtyCharacters() {
            when(repository.findProduct("jex3-0")).thenReturn(Optional.of(snapshot("jex3-0")));
            when(repository.slugEverUsed(any())).thenReturn(false);
            String longLabel = "x".repeat(80);

            NavService.ProductRenameResult result = service.updateProduct("jex3-0", longLabel, "layers", EDITOR);

            assertThat(result.product().key()).hasSize(60);
        }

        @Test
        @DisplayName("records a NAV_PRODUCT_RENAME audit entry — unlike today's label-only update, which records nothing")
        void auditsTheRename() {
            when(repository.findProduct("jex3-0")).thenReturn(Optional.of(snapshot("jex3-0")));
            when(repository.slugEverUsed("jex")).thenReturn(false);

            service.updateProduct("jex3-0", "Jex", "layers", EDITOR);

            verify(audit)
                    .append(
                            eq(EDITOR.id()),
                            eq(EDITOR.email()),
                            eq(com.coc.docs.audit.AuditAction.NAV_PRODUCT_RENAME),
                            eq(com.coc.docs.audit.AuditAction.TARGET_NAV_PRODUCT),
                            any(),
                            any());
        }
    }

    @Nested
    @DisplayName("custom groups")
    class Groups {

        @Test
        @DisplayName("creates a group, trimmed, once its name is unique")
        void createsGroup() {
            when(groupRepository.groupNameExists("Legal Team")).thenReturn(false);
            when(groupRepository.nextGroupOrder()).thenReturn(3);
            when(groupRepository.insertGroup("Legal Team", 3)).thenReturn(new NavGroup(UUID.randomUUID(), "Legal Team", false, 3));

            NavGroup created = service.createGroup("  Legal Team  ", EDITOR);

            assertThat(created.name()).isEqualTo("Legal Team");
            verify(groupRepository).insertGroup("Legal Team", 3);
        }

        @Test
        @DisplayName("refuses a blank group name")
        void refusesBlankGroupName() {
            assertThatThrownBy(() -> service.createGroup("   ", EDITOR))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).status())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("refuses a group name over 24 characters")
        void refusesOverlongGroupName() {
            assertThatThrownBy(() -> service.createGroup("x".repeat(25), EDITOR))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).status())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("refuses a duplicate group name, case-insensitively")
        void refusesDuplicateGroupName() {
            when(groupRepository.groupNameExists("legal")).thenReturn(true);

            assertThatThrownBy(() -> service.createGroup("legal", EDITOR))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).status())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("refuses to rename a built-in group")
        void refusesRenamingBuiltinGroup() {
            UUID id = UUID.randomUUID();
            when(groupRepository.findGroup(id)).thenReturn(Optional.of(new NavGroup(id, "ADMINISTRATION", true, 1)));

            assertThatThrownBy(() -> service.renameGroup(id, "Something Else", EDITOR))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).status())
                    .isEqualTo(HttpStatus.BAD_REQUEST);

            verify(groupRepository, never()).renameGroup(any(), any());
        }

        @Test
        @DisplayName("renames a custom group once the new name is unique")
        void renamesCustomGroup() {
            UUID id = UUID.randomUUID();
            when(groupRepository.findGroup(id)).thenReturn(Optional.of(new NavGroup(id, "Old Name", false, 3)));
            when(groupRepository.groupNameExistsExcluding("New Name", id)).thenReturn(false);

            service.renameGroup(id, "New Name", EDITOR);

            verify(groupRepository).renameGroup(id, "New Name");
        }

        @Test
        @DisplayName("refuses to delete a built-in group")
        void refusesDeletingBuiltinGroup() {
            UUID id = UUID.randomUUID();
            when(groupRepository.findGroup(id)).thenReturn(Optional.of(new NavGroup(id, "DEVELOPER", true, 2)));

            assertThatThrownBy(() -> service.deleteGroup(id, EDITOR))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).status())
                    .isEqualTo(HttpStatus.BAD_REQUEST);

            verify(groupRepository, never()).deleteGroup(any());
        }

        @Test
        @DisplayName("refuses to delete a custom group that still has items")
        void refusesDeletingNonEmptyGroup() {
            UUID id = UUID.randomUUID();
            when(groupRepository.findGroup(id)).thenReturn(Optional.of(new NavGroup(id, "Legal Team", false, 3)));
            when(groupRepository.itemCountForGroup(id)).thenReturn(2);

            assertThatThrownBy(() -> service.deleteGroup(id, EDITOR))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).status())
                    .isEqualTo(HttpStatus.CONFLICT);

            verify(groupRepository, never()).deleteGroup(any());
        }

        @Test
        @DisplayName("deletes a custom group once it has no items left")
        void deletesEmptyCustomGroup() {
            UUID id = UUID.randomUUID();
            when(groupRepository.findGroup(id)).thenReturn(Optional.of(new NavGroup(id, "Legal Team", false, 3)));
            when(groupRepository.itemCountForGroup(id)).thenReturn(0);

            service.deleteGroup(id, EDITOR);

            verify(groupRepository).deleteGroup(id);
        }
    }
}
