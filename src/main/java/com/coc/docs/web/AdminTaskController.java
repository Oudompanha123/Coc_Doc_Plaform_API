package com.coc.docs.web;

import com.coc.docs.auth.CurrentUser;
import com.coc.docs.tasks.TaskService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Overview landing page's "Your Tasks" panel (ADR-033). Sits under
 * {@code /api/v1/admin/**}, already covered by {@code SecurityConfig}'s
 * generic {@code admin}-role rule for that prefix — a task's title carries
 * no other account's PII, so it does not need {@code AdminAuditController}'s
 * {@code super_admin} floor.
 */
@RestController
@RequestMapping("/api/v1/admin/tasks")
public class AdminTaskController {

    private final TaskService tasks;

    public AdminTaskController(TaskService tasks) {
        this.tasks = tasks;
    }

    @GetMapping
    List<ApiDtos.TaskResponse> listOpen(@RequestParam(defaultValue = "10") int limit) {
        return tasks.listOpen(limit).stream().map(ApiDtos.TaskResponse::of).toList();
    }

    @PostMapping
    ResponseEntity<ApiDtos.TaskResponse> create(
            @Valid @RequestBody ApiDtos.CreateTaskRequest request, @AuthenticationPrincipal CurrentUser creator) {

        var created = tasks.create(request.title(), request.dueDate(), creator);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiDtos.TaskResponse.of(created));
    }

    @PostMapping("/{id}/complete")
    void complete(@PathVariable UUID id) {
        tasks.complete(id);
    }
}
