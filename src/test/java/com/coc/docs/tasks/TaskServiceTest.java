package com.coc.docs.tasks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.coc.docs.account.AppRole;
import com.coc.docs.auth.CurrentUser;
import com.coc.docs.auth.TestCallers;
import com.coc.docs.web.ApiException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class TaskServiceTest {

    private final TaskRepository repository = mock(TaskRepository.class);
    private final TaskService service = new TaskService(repository);

    private static final CurrentUser CREATOR = TestCallers.user(AppRole.ADMIN);

    @Test
    @DisplayName("refuses a blank title")
    void refusesBlankTitle() {
        assertThatThrownBy(() -> service.create("   ", null, CREATOR))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).status())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        verify(repository, org.mockito.Mockito.never()).insert(any(), any(), any());
    }

    @Test
    @DisplayName("creates a task with a trimmed title")
    void createsTask() {
        LocalDate due = LocalDate.of(2026, 9, 20);
        when(repository.insert("Write docs", due, CREATOR.id()))
                .thenReturn(new Task(UUID.randomUUID(), "Write docs", due, Task.STATUS_OPEN, CREATOR.id(), Instant.now(), null));

        Task created = service.create("  Write docs  ", due, CREATOR);

        assertThat(created.title()).isEqualTo("Write docs");
        verify(repository).insert("Write docs", due, CREATOR.id());
    }

    @Test
    @DisplayName("refuses to complete a task that does not exist")
    void refusesCompletingMissingTask() {
        UUID id = UUID.randomUUID();
        when(repository.exists(id)).thenReturn(false);

        assertThatThrownBy(() -> service.complete(id))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).status())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("refuses to complete a task that is already done")
    void refusesCompletingDoneTask() {
        UUID id = UUID.randomUUID();
        when(repository.exists(id)).thenReturn(true);
        when(repository.complete(id)).thenReturn(0);

        assertThatThrownBy(() -> service.complete(id))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).status())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("completes an open task")
    void completesTask() {
        UUID id = UUID.randomUUID();
        when(repository.exists(id)).thenReturn(true);
        when(repository.complete(id)).thenReturn(1);

        service.complete(id);

        verify(repository).complete(id);
    }
}
