package com.nexora.refdata.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Optional;

/** Droits et identité de l'utilisateur courant (jeton SSO). */
public final class Habilitations {

    private Habilitations() {}

    /** Vrai hors requête authentifiée (chargement initial, tâches internes) ou si le jeton porte la permission. */
    public static boolean a(String autorite) {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (!(a instanceof JwtAuthenticationToken)) return true;
        return a.getAuthorities().stream().anyMatch(g -> g.getAuthority().equals(autorite));
    }

    public static Optional<String> utilisateur() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a instanceof JwtAuthenticationToken j ? Optional.ofNullable(j.getToken().getClaimAsString("preferred_username")) : Optional.empty();
    }
}
