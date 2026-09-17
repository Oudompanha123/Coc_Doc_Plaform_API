package com.coc.docs.claudeusage;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/** One day of the dashboard's stacked chart (ADR-035), per-member breakdown for the stack. */
public record ClaudeUsageDailyPoint(LocalDate date, long total, Map<UUID, Long> byMember) {}
