package com.nexora.auth.account;

import java.time.OffsetDateTime;
import java.util.List;

/** Utilisateur avec ses habilitations effectives (rôles directs et hérités des groupes, permissions). */
public record Compte(long id, String username, String fullName, String email, String organisation, String language,
                     boolean active, boolean mustChangePassword, int failedAttempts, OffsetDateTime lockedUntil,
                     OffsetDateTime lastLoginAt, OffsetDateTime createdAt, List<String> directRoles, List<String> groups,
                     List<String> roles, List<String> permissions) {

    public boolean verrouille() {
        return lockedUntil != null && lockedUntil.isAfter(OffsetDateTime.now());
    }
}
