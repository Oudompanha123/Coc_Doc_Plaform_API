package com.coc.docs.web;

import com.coc.docs.account.RoleRepository;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The role catalogue — what each role is, what it grants, and how many people
 * hold it.
 *
 * <p>Read-only, and there is no write counterpart on purpose. Roles are the
 * four {@code app_role} values, seeded with their permissions by migration
 * (ADR-016); adding one is a schema change that should look like a schema
 * change rather than a row someone creates in a screen. This exists so an
 * administrator can see what "admin" actually permits instead of inferring it
 * from the word.
 *
 * <p>Its own path rather than {@code /admin/accounts/roles}, which would read
 * as "the roles of accounts". That means it needs its own rule in
 * {@code SecurityConfig} to sit under the {@code super_admin} floor — the
 * surrounding {@code /admin/**} only carries an {@code admin} floor, and who
 * may do what is the same class of question as who is who (FR-AUTH-008).
 *
 * <p>// req: FR-AUTH-005, FR-AUTH-008, ADR-016
 */
@RestController
@RequestMapping("/api/v1/admin/roles")
public class AdminRoleController {

    private final RoleRepository roles;

    public AdminRoleController(RoleRepository roles) {
        this.roles = roles;
    }

    /** Every role, lowest rank first — the order the ladder reads in. */
    @GetMapping
    List<ApiDtos.RoleResponse> list() {
        return roles.findAll().stream().map(ApiDtos.RoleResponse::of).toList();
    }
}
