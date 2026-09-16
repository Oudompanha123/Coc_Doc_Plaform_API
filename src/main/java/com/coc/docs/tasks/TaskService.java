package com.coc.docs.tasks;

import com.coc.docs.auth.CurrentUser;
import com.coc.docs.web.ApiException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Backs the Overview landing page's "Your Tasks" panel (ADR-033). */
@Service
public class TaskService {

    private final TaskRepository repository;

    public TaskService(TaskRepository repository) {
        this.repository = repository;
    }

    public Task create(String title, LocalDate dueDate, CurrentUser creator) {
        if (title == null || title.isBlank()) {
            throw ApiException.badRequest("title must not be blank");
        }
        return repository.insert(title.trim(), dueDate, creator.id());
    }

    public List<Task> listOpen(int limit) {
        int capped = Math.min(Math.max(limit, 1), 50);
        return repository.listOpen(capped);
    }

    public void complete(UUID id) {
        if (!repository.exists(id)) {
            throw ApiException.notFound("task " + id);
        }
        if (repository.complete(id) == 0) {
            throw ApiException.conflict("task " + id + " is already done");
        }
    }
}
