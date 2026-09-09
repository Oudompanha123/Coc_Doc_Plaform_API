package com.coc.docs.web;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Liveness.
 *
 * <p>Deliberately says nothing else: no version, no database status, no
 * configuration. It is the one unauthenticated route besides sign-in, and an
 * unauthenticated route that reports on internals is a free inventory of them.
 */
@RestController
@RequestMapping("/api/v1/health")
public class HealthController {

    @GetMapping
    Map<String, String> health() {
        return Map.of("status", "ok");
    }
}
