package com.coc.docs.notify;

import com.coc.docs.auth.CurrentUser;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The caller's own notification preferences — what {@code NotificationsCard}
 * on the Settings page reads and writes.
 *
 * <p>The list is always every {@link NotificationType} member, not just the
 * ones with a stored row (ADR-041): opt-out is by absence, so a type nobody
 * has touched yet is enabled and still needs to appear as a switch the caller
 * can flip.
 *
 * <p>// req: ADR-041
 */
@Service
public class NotificationPreferenceService {

    private final NotificationPreferenceRepository preferences;

    public NotificationPreferenceService(NotificationPreferenceRepository preferences) {
        this.preferences = preferences;
    }

    /** One entry per real {@link NotificationType}, defaulted to enabled where no row exists. */
    public record Preference(NotificationType type, boolean enabled) {}

    @Transactional(readOnly = true)
    public List<Preference> listFor(CurrentUser caller) {
        Map<String, Boolean> overrides = preferences.findOverridesFor(caller.id());
        return java.util.Arrays.stream(NotificationType.values())
                .map(type -> new Preference(type, overrides.getOrDefault(type.key(), true)))
                .toList();
    }

    @Transactional
    public void setEnabled(CurrentUser caller, NotificationType type, boolean enabled) {
        preferences.setEnabled(caller.id(), type, enabled);
    }
}
