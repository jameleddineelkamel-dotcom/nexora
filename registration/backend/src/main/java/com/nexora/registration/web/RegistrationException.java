package com.nexora.registration.web;

import org.springframework.http.HttpStatus;

import java.util.List;

/** Erreur métier renvoyée au client au format RFC 9457, avec la liste détaillée des erreurs. */
public class RegistrationException extends RuntimeException {

    private final HttpStatus status;
    private final List<String> erreurs;

    public RegistrationException(HttpStatus status, List<String> erreurs) {
        super(String.join(" ", erreurs));
        this.status = status;
        this.erreurs = List.copyOf(erreurs);
    }

    public static RegistrationException interdit(String message) {
        return new RegistrationException(HttpStatus.FORBIDDEN, List.of(message));
    }

    public static RegistrationException introuvable(String message) {
        return new RegistrationException(HttpStatus.NOT_FOUND, List.of(message));
    }

    public static RegistrationException invalide(List<String> erreurs) {
        return new RegistrationException(HttpStatus.BAD_REQUEST, erreurs);
    }

    public static RegistrationException invalide(String message) {
        return invalide(List.of(message));
    }

    public static RegistrationException conflit(String message) {
        return new RegistrationException(HttpStatus.CONFLICT, List.of(message));
    }

    public HttpStatus getStatus() { return status; }

    public List<String> getErreurs() { return erreurs; }
}
