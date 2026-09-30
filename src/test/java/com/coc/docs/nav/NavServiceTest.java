package com.coc.docs.nav;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import org.junit.jupiter.api.DisplayName;
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
    private final DocsContentRepository contentRepository = mock(DocsContentRepository.class);
    private final AuditRepository audit = mock(AuditRepository.class);
    private final NavService service = new NavService(repository, contentRepository, audit, new ObjectMapper());

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
}
