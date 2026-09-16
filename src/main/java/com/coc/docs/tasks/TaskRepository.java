package com.coc.docs.tasks;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Reads and writes {@code task} rows (V17, ADR-033). */
@Repository
public class TaskRepository {

    private final JdbcClient jdbc;

    public TaskRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private Task map(ResultSet rs, int rowNum) throws SQLException {
        Instant completedAt = rs.getObject("completed_at", OffsetDateTime.class) == null
                ? null
                : rs.getObject("completed_at", OffsetDateTime.class).toInstant();
        return new Task(
                rs.getObject("id", UUID.class),
                rs.getString("title"),
                rs.getObject("due_date", LocalDate.class),
                rs.getString("status"),
                rs.getObject("created_by", UUID.class),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                completedAt);
    }

    public Task insert(String title, LocalDate dueDate, UUID createdBy) {
        return jdbc.sql("""
                        insert into task (title, due_date, created_by)
                        values (?, ?, ?)
                        returning id, title, due_date, status, created_by, created_at, completed_at
                        """)
                .params(title, dueDate, createdBy)
                .query(this::map)
                .single();
    }

    /** Open tasks, soonest due date first — a task with no due date sorts
     *  after every dated one, then oldest-created first among those. */
    public List<Task> listOpen(int limit) {
        return jdbc.sql("""
                        select id, title, due_date, status, created_by, created_at, completed_at
                        from task
                        where status = 'open'
                        order by due_date is null, due_date, created_at
                        limit ?
                        """)
                .param(limit)
                .query(this::map)
                .list();
    }

    public boolean exists(UUID id) {
        Integer count = jdbc.sql("select count(*) from task where id = ?").param(id).query(Integer.class).single();
        return count != null && count > 0;
    }

    public int complete(UUID id) {
        return jdbc.sql("update task set status = 'done', completed_at = now() where id = ? and status = 'open'")
                .param(id)
                .update();
    }
}
