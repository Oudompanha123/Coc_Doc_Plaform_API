package com.coc.docs.web;

import com.coc.docs.audit.AuditRepository;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The first controller ever placed over {@link AuditRepository}'s read
 * methods — per that class's own Javadoc, this sits at the {@code
 * super_admin} floor, not the general {@code admin} floor the rest of
 * {@code /api/v1/admin/**} uses, because every row carries another
 * account's email (NFR-SEC-PII), the same reasoning
 * {@code /api/v1/admin/accounts/**} is gated on. See the dedicated
 * {@code SecurityConfig} line for this prefix.
 */
@RestController
@RequestMapping("/api/v1/admin/audit")
public class AdminAuditController {

    private final AuditRepository audit;

    public AdminAuditController(AuditRepository audit) {
        this.audit = audit;
    }

    @GetMapping("/recent")
    List<ApiDtos.ActivityEntryResponse> recent(@RequestParam(defaultValue = "10") int limit) {
        int capped = Math.min(Math.max(limit, 1), 50);
        return audit.recent(capped).stream().map(ApiDtos.ActivityEntryResponse::of).toList();
    }
}
