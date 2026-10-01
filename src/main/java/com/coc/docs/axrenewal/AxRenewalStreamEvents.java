package com.coc.docs.axrenewal;

import com.coc.docs.axrenewal.AxRenewalGenerationProgress.Activity;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns one line of the Claude Code CLI's {@code --output-format stream-json}
 * output into what the model just did, in words a person can follow (ADR-082).
 *
 * <p>Only what the stream actually says: a tool the model called, the file it
 * named, a note it wrote. An event this does not recognise produces nothing,
 * not a guess, so a newer CLI that adds event types degrades to a quieter
 * feed rather than a wrong one.
 *
 * <p><b>Paths are shown relative to the project</b>, never as this machine's
 * absolute paths: a file inside the staging copy is shown by its path within
 * it, and anything else by its file name alone. File contents never appear —
 * not from a Read's result, and an error is cut short.
 *
 * <p>// req: ADR-082
 */
final class AxRenewalStreamEvents {

    static final int NOTE_LIMIT = 240;
    static final int ERROR_LIMIT = 160;

    private AxRenewalStreamEvents() {}

    static List<Activity> parse(JsonNode event, Path stagingDir, Instant at) {
        List<Activity> out = new ArrayList<>();
        if (event == null || !event.isObject()) {
            return out;
        }
        switch (event.path("type").asText("")) {
            case "system" -> {
                if ("init".equals(event.path("subtype").asText(""))) {
                    out.add(new Activity(at, "note", "Connected to the local model — starting work", null));
                }
            }
            case "assistant" -> {
                for (JsonNode block : event.path("message").path("content")) {
                    String type = block.path("type").asText("");
                    if ("text".equals(type)) {
                        String text = clip(block.path("text").asText(""), NOTE_LIMIT);
                        if (!text.isEmpty()) {
                            out.add(new Activity(at, "note", text, null));
                        }
                    } else if ("tool_use".equals(type)) {
                        out.add(toolUse(block.path("name").asText(""), block.path("input"), stagingDir, at));
                    }
                    // thinking and anything newer: not shown
                }
            }
            case "user" -> {
                // Tool results carry file contents; only a failure is worth a line.
                for (JsonNode block : event.path("message").path("content")) {
                    if ("tool_result".equals(block.path("type").asText("")) && block.path("is_error").asBoolean(false)) {
                        String detail = clip(resultText(block.path("content")), ERROR_LIMIT);
                        out.add(new Activity(at, "error", detail.isEmpty() ? "A step failed" : "A step failed: " + detail, null));
                    }
                }
            }
            case "result" -> {
                boolean failed = event.path("is_error").asBoolean(false)
                        || !"success".equals(event.path("subtype").asText("success"));
                int turns = event.path("num_turns").asInt(0);
                String text = failed
                        ? "The model stopped without finishing (" + event.path("subtype").asText("error") + ")"
                        : turns > 0 ? "The model finished its work in " + turns + " turns" : "The model finished its work";
                out.add(new Activity(at, failed ? "error" : "done", text, null));
            }
            default -> {
                // stream events this does not know: nothing
            }
        }
        return out;
    }

    private static Activity toolUse(String name, JsonNode input, Path stagingDir, Instant at) {
        String file = shown(input.path("file_path").asText(input.path("path").asText("")), stagingDir);
        return switch (name) {
            case "Read" -> new Activity(at, "read", "Reading " + orSomething(file, "a file"), file);
            case "Edit", "MultiEdit" -> new Activity(at, "edit", "Editing " + orSomething(file, "a file"), file);
            case "Write" -> new Activity(at, "write", "Writing " + orSomething(file, "a new file"), file);
            case "NotebookEdit" -> new Activity(at, "edit", "Editing notebook " + orSomething(file, ""), file);
            case "Grep" -> new Activity(at, "search", "Searching for “" + clip(input.path("pattern").asText(""), 80) + "”", null);
            case "Glob" -> new Activity(at, "search", "Looking for files matching " + clip(input.path("pattern").asText(""), 80), null);
            case "LS" -> new Activity(at, "list", "Listing " + orSomething(file, "a folder"), file);
            case "TodoWrite" -> new Activity(at, "plan", planText(input.path("todos")), null);
            case "Task", "Agent" -> new Activity(at, "tool", "Handing off: " + clip(input.path("description").asText("a sub-task"), 80), null);
            default -> new Activity(at, "tool", "Using " + (name.isBlank() ? "a tool" : name), null);
        };
    }

    /** "Updated its plan — 2 of 5 steps done" from TodoWrite's own list. */
    private static String planText(JsonNode todos) {
        int total = 0;
        int done = 0;
        String current = null;
        for (JsonNode todo : todos) {
            total++;
            String status = todo.path("status").asText("");
            if ("completed".equals(status)) {
                done++;
            } else if ("in_progress".equals(status) && current == null) {
                current = clip(todo.path("activeForm").asText(todo.path("content").asText("")), 80);
            }
        }
        if (total == 0) {
            return "Updated its plan";
        }
        String base = "Updated its plan — " + done + " of " + total + " steps done";
        return current == null || current.isEmpty() ? base : base + "; now: " + current;
    }

    /** A path as it may be shown: relative inside the staging copy, otherwise the file name alone. */
    static String shown(String raw, Path stagingDir) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            Path p = Path.of(raw);
            if (stagingDir != null && p.isAbsolute()) {
                Path root = stagingDir.toAbsolutePath().normalize();
                Path normal = p.normalize();
                if (normal.startsWith(root)) {
                    String rel = root.relativize(normal).toString().replace('\\', '/');
                    return rel.isEmpty() ? "the project folder" : rel;
                }
                Path name = normal.getFileName();
                return name == null ? null : name.toString();
            }
            if (p.isAbsolute()) {
                Path name = p.getFileName();
                return name == null ? null : name.toString();
            }
            return raw.replace('\\', '/');
        } catch (InvalidPathException e) {
            return null;
        }
    }

    private static String resultText(JsonNode content) {
        if (content.isTextual()) {
            return content.asText();
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode part : content) {
            if ("text".equals(part.path("type").asText(""))) {
                sb.append(part.path("text").asText("")).append(' ');
            }
        }
        return sb.toString();
    }

    private static String orSomething(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /** Whitespace collapsed to single spaces, then cut at {@code limit} with an ellipsis. */
    static String clip(String text, int limit) {
        String flat = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        return flat.length() <= limit ? flat : flat.substring(0, limit - 1).trim() + "…";
    }
}
