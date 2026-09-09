package com.coc.docs.section;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads and writes {@code section} rows.
 *
 * <p>// req: FR-NAV-005, CONST-OPS-03
 */
@Repository
public class SectionRepository {

    /**
     * {@code order} is a reserved word in SQL, so the column is quoted in the
     * schema and must be quoted in every statement that names it.
     */
    private static final String COLUMNS = "id, slug, \"order\", db_backed, created_at";

    private static final RowMapper<Section> MAPPER = SectionRepository::map;

    private final JdbcClient jdbc;

    public SectionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static Section map(ResultSet rs, int rowNum) throws SQLException {
        return new Section(
                rs.getObject("id", UUID.class),
                rs.getString("slug"),
                rs.getInt("order"),
                rs.getBoolean("db_backed"),
                rs.getObject("created_at", java.time.OffsetDateTime.class).toInstant());
    }

    public List<Section> findAll() {
        return jdbc.sql("select " + COLUMNS + " from section order by \"order\", slug")
                .query(MAPPER)
                .list();
    }

    public Optional<Section> findBySlug(String slug) {
        return jdbc.sql("select " + COLUMNS + " from section where slug = ?")
                .param(slug)
                .query(MAPPER)
                .optional();
    }

    public Optional<Section> findById(UUID id) {
        return jdbc.sql("select " + COLUMNS + " from section where id = ?")
                .param(id)
                .query(MAPPER)
                .optional();
    }

    public Section insert(String slug, int order, boolean dbBacked) {
        return jdbc.sql("""
                        insert into section (slug, "order", db_backed)
                        values (?, ?, ?)
                        returning id, slug, "order", db_backed, created_at
                        """)
                .params(slug, order, dbBacked)
                .query(MAPPER)
                .single();
    }

    /** The per-section migration switch and its rollback. // req: CONST-OPS-03 */
    public int setDbBacked(UUID id, boolean dbBacked) {
        return jdbc.sql("update section set db_backed = ? where id = ?")
                .params(dbBacked, id)
                .update();
    }
}
