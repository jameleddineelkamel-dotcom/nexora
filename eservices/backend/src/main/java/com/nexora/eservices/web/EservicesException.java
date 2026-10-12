package com.nexora.eservices.web;

import org.springframework.http.HttpStatus;

import java.util.List;

/** Erreur métier renvoyée au client au format RFC 9457, avec la liste détaillée des erreurs. */
public class EservicesException extends RuntimeException {

    private final HttpStatus status;
    private final List<String> erreurs;

    public EservicesException(HttpStatus status, List<String> erreurs) {
        super(String.join(" ", erreurs));
        this.status = status;
        this.erreurs = List.copyOf(erreurs);
    }

    public static EservicesException interdit(String message) {
        return new EservicesException(HttpStatus.FORBIDDEN, List.of(message));
    }

    public static EservicesException indisponible(String message) {
        return new EservicesException(HttpStatus.SERVICE_UNAVAILABLE, List.of(message));
    }

    public static EservicesException introuvable(String message) {
        return new EservicesException(HttpStatus.NOT_FOUND, List.of(message));
    }

    public static EservicesException invalide(List<String> erreurs) {
        return new EservicesException(HttpStatus.BAD_REQUEST, erreurs);
    }

    public static EservicesException invalide(String message) {
        return invalide(List.of(message));
    }

    public static EservicesException conflit(String message) {
        return new EservicesException(HttpStatus.CONFLICT, List.of(message));
    }

    public HttpStatus getStatus() { return status; }

    public List<String> getErreurs() { return erreurs; }
}
