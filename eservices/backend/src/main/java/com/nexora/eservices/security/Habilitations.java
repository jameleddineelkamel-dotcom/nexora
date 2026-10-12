package com.nexora.eservices.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Optional;

/** Droits et identité de l'utilisateur courant (jeton SSO). Hors requête (tâches du moteur), l'acteur est « systeme ». */
public final class Habilitations {

    private Habilitations() {}

    /** Vrai si le jeton porte le droit (ex. « eservices.traiter ») ; vrai aussi hors requête authentifiée (moteur, amorçage). */
    public static boolean a(String droit) {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (!(a instanceof JwtAuthenticationToken)) return true;
        String autorite = droit.startsWith("PERM_") ? droit : "PERM_" + droit;
        return a.getAuthorities().stream().anyMatch(g -> g.getAuthority().equals(autorite));
    }

    /** Vrai seulement pour un utilisateur authentifié qui porte le droit (pas de passe-droit « système »). */
    public static boolean utilisateurA(String droit) {
        return SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken && a(droit);
    }

    public static Optional<String> utilisateur() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a instanceof JwtAuthenticationToken j ? Optional.ofNullable(j.getToken().getClaimAsString("preferred_username")) : Optional.empty();
    }

    public static String nom() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a instanceof JwtAuthenticationToken j ? Optional.ofNullable(j.getToken().getClaimAsString("name")).orElse(j.getName()) : "Système";
    }

    /** Jeton de l'appel en cours, transmis aux autres microservices (registre des opérateurs, studio). */
    public static Optional<String> jeton() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a instanceof JwtAuthenticationToken j ? Optional.of(j.getToken().getTokenValue()) : Optional.empty();
    }

    public static String acteur() {
        return utilisateur().orElse("systeme");
    }
}
